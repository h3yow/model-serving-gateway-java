package com.github.h3yow.modelservinggatewayjava.service;

import com.github.h3yow.modelservinggatewayjava.backend.InferenceBackend;
import com.github.h3yow.modelservinggatewayjava.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.circuit.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.exception.BackendException;
import com.github.h3yow.modelservinggatewayjava.exception.GatewayException;
import com.github.h3yow.modelservinggatewayjava.model.ModelVersion;
import com.github.h3yow.modelservinggatewayjava.model.RejectReason;
import com.github.h3yow.modelservinggatewayjava.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.model.ScoreResponse;
import com.github.h3yow.modelservinggatewayjava.routing.RouteDecision;
import com.github.h3yow.modelservinggatewayjava.routing.WeightedRouter;
import com.github.h3yow.modelservinggatewayjava.version.VersionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 网关核心流程：校验 -> 版本解析/灰度路由 -> 熔断检查 -> 缓存(single-flight) -> 后端计算。
 *
 * <p>不变式：</p>
 * <ul>
 *   <li>未注册/停用/不兼容版本以可区分原因拒绝，绝不静默回退默认版本；</li>
 *   <li>后端超时、非法请求、内部错误映射为不同且稳定的对外结果；</li>
 *   <li>熔断期间不再调用后端，已完成的缓存结果不受影响；</li>
 *   <li>每次请求输出一条可解释日志：请求、命中版本、判定依据。</li>
 * </ul>
 */
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final VersionRegistry registry;
    private final WeightedRouter router;
    private final Map<String, InferenceBackend> backends;
    private final Map<String, CircuitBreaker> breakers;
    private final ScoringCache cache;
    private final long backendTimeoutMs;
    private final ExecutorService backendExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ScoringService(VersionRegistry registry,
                          WeightedRouter router,
                          Map<String, InferenceBackend> backends,
                          Map<String, CircuitBreaker> breakers,
                          ScoringCache cache,
                          long backendTimeoutMs) {
        this.registry = registry;
        this.router = router;
        this.backends = backends;
        this.breakers = breakers;
        this.cache = cache;
        this.backendTimeoutMs = backendTimeoutMs;
    }

    /**
     * 单条评分。请求非法时抛 GatewayException(INVALID_REQUEST)；
     * 版本与后端问题以失败 ScoreResponse 返回（可区分 reason）。
     */
    public ScoreResponse score(ScoreRequest request) {
        validate(request);

        ModelVersion target;
        String routeBasis;
        if (request.modelVersion() != null) {
            // 精确版本：必须命中注册表，任何不匹配都拒绝
            ModelVersion mv = registry.find(request.modelName(), request.modelVersion());
            if (mv == null) {
                return reject(request, RejectReason.VERSION_NOT_REGISTERED,
                        "version not registered: " + request.modelVersion(),
                        "pinned version lookup failed");
            }
            if (!mv.enabled()) {
                return reject(request, RejectReason.VERSION_DISABLED,
                        "version disabled: " + request.modelVersion(),
                        "pinned version is disabled");
            }
            if (mv.weight() <= 0) {
                return reject(request, RejectReason.VERSION_INCOMPATIBLE,
                        "version weight=0, not serving: " + request.modelVersion(),
                        "pinned version has zero weight");
            }
            target = mv;
            routeBasis = "pinned version=" + mv.version();
        } else {
            List<ModelVersion> candidates = registry.candidates(request.modelName());
            if (candidates.isEmpty()) {
                return reject(request, RejectReason.NO_CANDIDATE_VERSION,
                        "no enabled version for model: " + request.modelName(),
                        "no enabled candidate");
            }
            RouteDecision decision = router.route(candidates, null);
            if (decision == null) {
                return reject(request, RejectReason.NO_CANDIDATE_VERSION,
                        "all candidate weights are zero for model: " + request.modelName(),
                        "total weight is zero");
            }
            target = decision.version();
            routeBasis = decision.basis();
        }

        CircuitBreaker breaker = breakers.get(target.backendId());

        long cfgVersion = registry.configVersion();
        String cacheKey = request.modelName() + "|" + target.version() + "|" + request.featureHash();
        long startNanos = System.nanoTime();

        try {
            // 熔断检查放在缓存未命中的加载器内：熔断期间已完成的缓存结果仍可返回（降级），
            // 只有需要真正调用后端时才被熔断器拦截。
            ScoringCache.Lookup lookup = cache.getOrLoad(cacheKey, cfgVersion, () -> {
                if (breaker != null && !breaker.allowRequest()) {
                    throw new GatewayException(RejectReason.CIRCUIT_OPEN,
                            "circuit open for backend: " + target.backendId());
                }
                return compute(request, target, breaker, routeBasis);
            });
            ScoreResponse result = lookup.future().join();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info("[score] request={}|version={}|hash={} -> hit={} version={} backend={} basis={}",
                    request.modelName(), request.modelVersion(), request.featureHash(),
                    lookup.hit(), result.modelVersion(), result.backendId(), routeBasis);
            // 命中缓存时返回带 cacheHit=true 的新对象，缓存内的原始结果不被改写
            return new ScoreResponse(result.success(), result.modelName(), result.modelVersion(),
                    result.backendId(), result.score(), result.reason(), result.message(),
                    lookup.hit(), elapsedMs, result.decision());
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof GatewayException ge) {
                return reject(request, ge.getReason(), ge.getMessage(),
                        routeBasis + " | compute failed");
            }
            return reject(request, RejectReason.BACKEND_INTERNAL_ERROR,
                    "unexpected: " + cause.getMessage(), routeBasis + " | compute failed");
        }
    }

    /** 实际调用后端（带超时），只在成功时缓存。 */
    private ScoreResponse compute(ScoreRequest request, ModelVersion target,
                                  CircuitBreaker breaker, String routeBasis) {
        InferenceBackend backend = backends.get(target.backendId());
        if (backend == null) {
            throw new GatewayException(RejectReason.BACKEND_INTERNAL_ERROR,
                    "backend not found: " + target.backendId());
        }
        long startNanos = System.nanoTime();
        Future<Double> future = backendExecutor.submit(() -> backend.score(request));
        try {
            double score = future.get(backendTimeoutMs, TimeUnit.MILLISECONDS);
            if (breaker != null) {
                breaker.recordSuccess();
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return ScoreResponse.success(request.modelName(), target.version(), backend.id(),
                    score, false, elapsedMs,
                    routeBasis + " | backend=" + backend.id() + " computed");
        } catch (TimeoutException e) {
            future.cancel(true);
            if (breaker != null) {
                breaker.recordFailure();
            }
            throw new BackendException(RejectReason.BACKEND_TIMEOUT,
                    "backend " + backend.id() + " timed out after " + backendTimeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BackendException be) {
                if (breaker != null && be.getReason() != RejectReason.BACKEND_ILLEGAL_ARGUMENT) {
                    // 非法请求是调用方问题，不计入后端健康度
                    breaker.recordFailure();
                }
                throw be;
            }
            if (breaker != null) {
                breaker.recordFailure();
            }
            throw new BackendException(RejectReason.BACKEND_INTERNAL_ERROR,
                    "backend " + backend.id() + " error: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(RejectReason.BACKEND_INTERNAL_ERROR, "interrupted");
        }
    }

    /** 批量评分：并发执行，相同请求在缓存层 single-flight 去重。 */
    public List<ScoreResponse> scoreBatch(List<ScoreRequest> requests, int parallelism) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return requests.parallelStream()
                .map(this::score)
                .toList();
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new TreeMap<>();
        stats.put("configVersion", registry.configVersion());
        stats.put("cacheSize", cache.size());
        stats.put("cacheHits", cache.hitCount());
        stats.put("traffic", router.trafficSnapshot());
        Map<String, String> breakerStates = new TreeMap<>();
        breakers.forEach((id, b) -> breakerStates.put(id, b.state().name()
                + "(failures=" + b.consecutiveFailures() + ")"));
        stats.put("circuitBreakers", breakerStates);
        return stats;
    }

    public void invalidateCache() {
        cache.invalidate();
    }

    private void validate(ScoreRequest request) {
        if (request == null) {
            throw new GatewayException(RejectReason.INVALID_REQUEST, "request body is required");
        }
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new GatewayException(RejectReason.INVALID_REQUEST, "modelName is required");
        }
        if (request.featureHash() == null || request.featureHash().isBlank()) {
            throw new GatewayException(RejectReason.INVALID_REQUEST, "featureHash is required");
        }
    }

    private ScoreResponse reject(ScoreRequest request, RejectReason reason,
                                 String message, String basis) {
        log.info("[reject] request={}|version={}|hash={} -> reason={} basis={}",
                request.modelName(), request.modelVersion(), request.featureHash(),
                reason, basis);
        return ScoreResponse.failure(request.modelName(), request.modelVersion(),
                reason, message, basis);
    }
}
