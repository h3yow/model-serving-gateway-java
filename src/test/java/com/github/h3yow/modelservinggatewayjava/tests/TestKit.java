package com.github.h3yow.modelservinggatewayjava.tests;

import com.github.h3yow.modelservinggatewayjava.backend.InMemoryBackend;
import com.github.h3yow.modelservinggatewayjava.backend.InferenceBackend;
import com.github.h3yow.modelservinggatewayjava.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.circuit.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.routing.WeightedRouter;
import com.github.h3yow.modelservinggatewayjava.service.ScoringService;
import com.github.h3yow.modelservinggatewayjava.version.VersionRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试装配工具：手工构建网关组件，避免依赖 Spring 上下文与真实时间配置。
 */
public final class TestKit {

    private TestKit() {
    }

    public static ScoreRequest req(String version, String featureHash) {
        return new ScoreRequest("fraud", version, featureHash, Map.of());
    }

    /** 可统计调用次数的内存后端包装。 */
    public static final class CountingBackend implements InferenceBackend {
        private final InMemoryBackend delegate;
        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        public CountingBackend(String id, long latencyMs, String failure) {
            this.delegate = new InMemoryBackend(id, latencyMs, failure);
        }

        public void setInjectedFailure(String failure) {
            delegate.setInjectedFailure(failure);
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public double score(ScoreRequest request) {
            calls.incrementAndGet();
            return delegate.score(request);
        }

        public int calls() {
            return calls.get();
        }
    }

    public static final class Env {
        public VersionRegistry registry;
        public WeightedRouter router;
        public ScoringCache cache;
        public ScoringService service;
        public Map<String, InferenceBackend> backends = new HashMap<>();
        public Map<String, CircuitBreaker> breakers = new HashMap<>();
        public CountingBackend backendA;
        public CountingBackend backendB;
    }

    /**
     * 构建标准双后端(v1->backend-a, v2->backend-b)测试环境。
     *
     * @param weightV1 v1 灰度权重
     * @param weightV2 v2 灰度权重
     * @param timeoutMs 后端调用超时
     * @param failureThreshold 熔断阈值
     * @param openStateMs 熔断打开冷却毫秒
     */
    public static Env env(int weightV1, int weightV2, long timeoutMs,
                          int failureThreshold, long openStateMs) {
        Env e = new Env();
        e.backendA = new CountingBackend("backend-a", 0, InMemoryBackend.FAIL_NONE);
        e.backendB = new CountingBackend("backend-b", 0, InMemoryBackend.FAIL_NONE);
        e.backends.put(e.backendA.id(), e.backendA);
        e.backends.put(e.backendB.id(), e.backendB);
        e.breakers.put(e.backendA.id(), new CircuitBreaker(failureThreshold, openStateMs));
        e.breakers.put(e.backendB.id(), new CircuitBreaker(failureThreshold, openStateMs));

        e.registry = new VersionRegistry(java.util.List.of(
                new com.github.h3yow.modelservinggatewayjava.model.ModelVersion(
                        "fraud", "v1", "backend-a", true, weightV1),
                new com.github.h3yow.modelservinggatewayjava.model.ModelVersion(
                        "fraud", "v2", "backend-b", true, weightV2)));
        e.router = new WeightedRouter();
        e.cache = new ScoringCache(60_000L);
        e.service = new ScoringService(e.registry, e.router, e.backends, e.breakers,
                e.cache, timeoutMs);
        return e;
    }
}
