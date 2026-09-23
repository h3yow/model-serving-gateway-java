package com.github.h3yow.modelservinggatewayjava.gateway.service;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendException;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.InferenceBackend;
import com.github.h3yow.modelservinggatewayjava.gateway.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.gateway.model.BatchScoreResponse;
import com.github.h3yow.modelservinggatewayjava.gateway.model.DecisionReason;
import com.github.h3yow.modelservinggatewayjava.gateway.model.GatewayErrorCode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreakerRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ConfigEpoch;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ModelVersionSpec;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.RoutingDecision;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.RoutingException;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.CanaryRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 评分服务：编排 校验 -> 路由 -> 熔断/降级 -> 缓存去重 -> 后端调用。
 */
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final CanaryRouter router;
    private final BackendRegistry backends;
    private final CircuitBreakerRegistry circuits;
    private final ScoringCache cache;
    private final ConfigEpoch epoch;

    public ScoringService(CanaryRouter router, BackendRegistry backends,
                          CircuitBreakerRegistry circuits, ScoringCache cache, ConfigEpoch epoch) {
        this.router = router;
        this.backends = backends;
        this.circuits = circuits;
        this.cache = cache;
        this.epoch = epoch;
        // 纪元推进（版本升级/权重调整）时立即清空缓存
        this.cache.bindEpoch(new ScoringCache.ConfigEpochView() {
            @Override
            public void onChange(java.util.function.Consumer<Long> listener) {
                epoch.onChange(listener);
            }
        });
    }

    public ScoreResult score(ScoreRequest request) {
        String reqId = request == null ? "null" : request.requestId();
        log.info("[score] request received requestId={} requestedVersion={} features={}",
                reqId, request == null ? null : request.requestedVersion(),
                request == null ? null : request.features());

        ScoreResult result = doScore(request);
        log.info("[score] request resolved requestId={} success={} servedVersion={} backend={} "
                        + "fromCache={} decisionReason={} errorCode={} errorMessage={}",
                result.requestId(), result.success(), result.servedVersion(), result.backendId(),
                result.fromCache(), result.decisionReason(), result.errorCode(),
                result.errorMessage());
        return result;
    }

    private ScoreResult doScore(ScoreRequest request) {
        String reqId = request == null ? "null" : request.requestId();

        // 1) 请求合法性校验（非法请求不参与熔断统计）
        String invalid = validate(request);
        if (invalid != null) {
            return ScoreResult.error(reqId, GatewayErrorCode.INVALID_REQUEST.name(),
                    invalid, "REJECTED_INVALID_REQUEST");
        }

        // 2) 路由（未注册/零权重等在此给出可区分错误，绝不静默回退）
        RoutingDecision decision;
        ModelVersionSpec spec;
        try {
            decision = router.route(request.requestedVersion());
            spec = router.specOf(decision.version());
        } catch (RoutingException e) {
            return ScoreResult.error(reqId, e.getErrorCode().name(), e.getMessage(),
                    "REJECTED_BY_ROUTER");
        }

        // 3) 版本兼容性校验（特征维度）
        int dim = request.features().size();
        if (spec.expectedFeatureDim() != dim) {
            return ScoreResult.error(reqId, GatewayErrorCode.VERSION_INCOMPATIBLE.name(),
                    "version '" + spec.version() + "' expects feature dimension "
                            + spec.expectedFeatureDim() + " but request has " + dim,
                    "REJECTED_INCOMPATIBLE");
        }

        String featuresKey = featuresKey(request);
        long currentEpoch = epoch.current();

        // 4) 缓存去重 + 后端调用（并发相同请求只计算一次）
        ScoreResult result = cache.computeIfAbsent(
                spec.version(), reqId, featuresKey, currentEpoch,
                () -> invokeWithFallback(request, decision, spec));
        return result;
    }

    /**
     * 熔断 + 分级降级：
     * 依次尝试该版本的后端，熔断打开的后端直接跳过；
     * 超时/内部错误记录熔断并重试其它健康后端；非法请求立即返回不降级。
     * 已由调用方拿到的成功结果不会随后续熔断被改写。
     */
    private ScoreResult invokeWithFallback(ScoreRequest request, RoutingDecision decision,
                                           ModelVersionSpec spec) {
        String reqId = request.requestId();
        List<InferenceBackend> candidates = new ArrayList<>(backends.backendsFor(spec.version()));
        if (candidates.isEmpty()) {
            return ScoreResult.error(reqId, GatewayErrorCode.ALL_BACKENDS_UNAVAILABLE.name(),
                    "no backend deployed for version '" + spec.version() + "'",
                    "NO_BACKEND_DEPLOYED");
        }

        List<String> attemptNotes = new ArrayList<>();
        BackendException lastBackendError = null;

        for (InferenceBackend backend : candidates) {
            CircuitBreaker cb = circuits.forBackend(backend.id());
            if (!cb.allowRequest()) {
                attemptNotes.add(backend.id() + ":CIRCUIT_OPEN_SKIPPED");
                continue;
            }
            try {
                double value = backend.score(request);
                cb.onSuccess();
                String reason = attemptNotes.isEmpty()
                        ? decision.reason()
                        : DecisionReason.FALLBACK_HEALTHY_BACKEND;
                return ScoreResult.ok(reqId, value, spec.version(), backend.id(), false,
                        annotate(decision, attemptNotes, backend.id(), reason));
            } catch (BackendException e) {
                lastBackendError = e;
                GatewayErrorCode code = e.getErrorCode();
                if (code == GatewayErrorCode.INVALID_REQUEST) {
                    // 非法请求：分级处理，立即失败，不熔断不降级
                    return ScoreResult.error(reqId, code.name(), e.getMessage(),
                            "BACKEND_REJECTED_INVALID");
                }
                // 超时 / 内部错误：计入熔断并尝试降级
                cb.onFailure();
                attemptNotes.add(backend.id() + ":" + code.name() + ":CB_STATE=" + cb.state());
            }
        }

        GatewayErrorCode finalCode = lastBackendError == null
                ? GatewayErrorCode.ALL_BACKENDS_UNAVAILABLE
                : lastBackendError.getErrorCode();
        String message = lastBackendError == null
                ? "all backends for version '" + spec.version() + "' are circuit-open"
                : "all backends failed for version '" + spec.version()
                        + "'; last error: " + lastBackendError.getMessage();
        return ScoreResult.error(reqId, finalCode.name(), message,
                annotate(decision, attemptNotes, null, DecisionReason.CIRCUIT_OPEN_SKIPPED));
    }

    private static String annotate(RoutingDecision decision, List<String> attempts,
                                   String chosenBackend, String reason) {
        StringBuilder sb = new StringBuilder(reason);
        sb.append("(family=").append(decision.family())
                .append(",pinned=").append(decision.pinned())
                .append(",weight=").append(decision.weightUsed());
        if (!attempts.isEmpty()) {
            sb.append(",attempts=").append(attempts);
        }
        if (chosenBackend != null) {
            sb.append(",chosen=").append(chosenBackend);
        }
        sb.append(")");
        return sb.toString();
    }

    private static String validate(ScoreRequest r) {
        if (r == null) {
            return "request body is null";
        }
        if (r.requestedVersion() == null || r.requestedVersion().isBlank()) {
            return "requestedVersion must not be blank; no implicit default version";
        }
        if (r.features() == null || r.features().isEmpty()) {
            return "features must be a non-empty list";
        }
        for (Double d : r.features()) {
            if (d == null || Double.isNaN(d) || Double.isInfinite(d)) {
                return "features must contain only finite numbers";
            }
        }
        return null;
    }

    private static String featuresKey(ScoreRequest r) {
        StringBuilder sb = new StringBuilder();
        for (Double d : r.features()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", d));
        }
        return sb.toString();
    }

    /**
     * 批量评分：逐条独立处理，已完成结果不会被后续失败/熔断改写。
     * 相同请求在缓存层去重，并发只计算一次。
     */
    public BatchScoreResponse batch(List<ScoreRequest> requests) {
        List<ScoreRequest> safe = requests == null ? List.of() : requests;
        List<ScoreResult> results = new ArrayList<>(safe.size());
        int hits = 0;
        int computed = 0;
        for (ScoreRequest r : safe) {
            ScoreResult res = score(r);
            results.add(res);
            if (res.fromCache()) {
                hits++;
            } else if (res.success()) {
                computed++;
            }
        }
        return new BatchScoreResponse(results, epoch.current(), hits, computed);
    }
}
