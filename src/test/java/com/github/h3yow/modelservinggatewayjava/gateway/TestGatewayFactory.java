package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendType;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.InMemoryInferenceBackend;
import com.github.h3yow.modelservinggatewayjava.gateway.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreakerRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.RecoveryPolicy;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.TimeSource;
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

    /** 可计数的内存后端，用于断言“实际计算次数”。 */
    public static class CountingBackend extends InMemoryInferenceBackend {
        public final AtomicInteger invocations = new AtomicInteger(0);

        public CountingBackend(String id, BackendType type, String version, long latencyMs) {
            super(id, type, version, latencyMs);
        }

        @Override
        public double score(com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest r) {
            invocations.incrementAndGet();
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
        return build(new RecoveryPolicy(circuitThreshold, openDurationMs, 1.0d, openDurationMs),
                TimeSource.SYSTEM, cacheTtlMs, backendLatencyMs);
    }

    /**
     * 完整构建：可注入自愈退避策略与时间源（测试用手动时钟确定性推进冷却）。
     */
    public static Harness build(RecoveryPolicy policy, TimeSource clock, long cacheTtlMs,
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

        CircuitBreakerRegistry circuits =
                new CircuitBreakerRegistry(policy, clock,
                        com.github.h3yow.modelservinggatewayjava.gateway.resilience
                                .CircuitBreaker.DEFAULT_TIMELINE_CAPACITY);
        ScoringCache cache = new ScoringCache(cacheTtlMs);
        ScoringService service = new ScoringService(router, backends, circuits, cache, epoch);
        return new Harness(service, router, backends, circuits, cache, epoch);
    }

    /** 手动时钟：测试确定性推进冷却与退避。 */
    public static final class ManualClock implements TimeSource {
        private final java.util.concurrent.atomic.AtomicLong now;

        public ManualClock(long startMs) {
            this.now = new java.util.concurrent.atomic.AtomicLong(startMs);
        }

        @Override
        public long nowMs() {
            return now.get();
        }

        public void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    public static CountingBackend counting(BackendRegistry registry, String backendId) {
        return (CountingBackend) registry.findById(backendId);
    }
}
