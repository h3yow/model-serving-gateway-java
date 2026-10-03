package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendType;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.FailureMode;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.InMemoryInferenceBackend;
import com.github.h3yow.modelservinggatewayjava.gateway.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreakerConfig;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreakerRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.CanaryRouter;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ConfigEpoch;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ModelVersionSpec;
import com.github.h3yow.modelservinggatewayjava.gateway.service.ScoringService;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试装配：手工构建内存网关，组件与生产装配完全一致，参数可控。
 */
public final class TestGatewayFactory {

    /** 可计数的内存后端，用于断言“实际计算次数”，并支持注入并发生闸门。 */
    public static class CountingBackend extends InMemoryInferenceBackend {
        public final AtomicInteger invocations = new AtomicInteger(0);

        /** 非 null 时在正常评分路径内先执行该钩子（用于阻塞试探请求，制造并发窗口）。 */
        private volatile Runnable gate;

        public CountingBackend(String id, BackendType type, String version, long latencyMs) {
            super(id, type, version, latencyMs);
        }

        public void setGate(Runnable gate) {
            this.gate = gate;
        }

        @Override
        public double score(com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest r) {
            invocations.incrementAndGet();
            Runnable g = gate;
            if (g != null && failureMode() == FailureMode.NONE) {
                g.run();
            }
            return super.score(r);
        }
    }

    public record Harness(ScoringService service, CanaryRouter router, BackendRegistry backends,
                          CircuitBreakerRegistry circuits, ScoringCache cache,
                          ConfigEpoch epoch) {
    }

    private TestGatewayFactory() {
    }

    /**
     * 构建双版本（v1 dim=3 两个后端，v2 dim=4 两个后端）灰度网关。
     */
    public static Harness build(int circuitThreshold, long openDurationMs, long cacheTtlMs,
                                long backendLatencyMs) {
        return build(new CircuitBreakerConfig(circuitThreshold, openDurationMs,
                1.0d, openDurationMs, 20), cacheTtlMs, backendLatencyMs);
    }

    /**
     * 构建带完整自愈策略（首次冷却/退避乘数/冷却上限/时间线容量）的网关。
     */
    public static Harness build(CircuitBreakerConfig circuitConfig, long cacheTtlMs,
                                long backendLatencyMs) {
        ConfigEpoch epoch = new ConfigEpoch();
        CanaryRouter router = new CanaryRouter(epoch);
        router.registerVersion(new ModelVersionSpec("v1", "ml-score", 3));
        router.registerVersion(new ModelVersionSpec("v2", "ml-score", 4));
        router.seedWeights("ml-score", Map.of("v1", 100, "v2", 0));

        BackendRegistry backends = new BackendRegistry();
        backends.register(new CountingBackend("cpu-v1-1", BackendType.CPU, "v1", backendLatencyMs));
        backends.register(new CountingBackend("cpu-v1-2", BackendType.CPU, "v1", backendLatencyMs));
        backends.register(new CountingBackend("gpu-v2-1", BackendType.GPU, "v2", backendLatencyMs));
        backends.register(new CountingBackend("edge-v2-1", BackendType.EDGE, "v2", backendLatencyMs));

        CircuitBreakerRegistry circuits = new CircuitBreakerRegistry(circuitConfig);
        ScoringCache cache = new ScoringCache(cacheTtlMs);
        ScoringService service = new ScoringService(router, backends, circuits, cache, epoch);
        return new Harness(service, router, backends, circuits, cache, epoch);
    }

    public static CountingBackend counting(BackendRegistry registry, String backendId) {
        return (CountingBackend) registry.findById(backendId);
    }
}
