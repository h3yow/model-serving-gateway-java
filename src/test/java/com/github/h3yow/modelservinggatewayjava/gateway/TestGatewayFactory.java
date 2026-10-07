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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

    /**
     * 可阻塞的计数后端：进入真实评分后在指定闸门处等待，用于把“试探请求”
     * 钉在在飞状态，确定性地验证同一时刻只有一个试探请求。
     */
    public static class BlockingCountingBackend extends InMemoryInferenceBackend {
        public final AtomicInteger invocations = new AtomicInteger(0);
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        public BlockingCountingBackend(String id, BackendType type, String version) {
            super(id, type, version, 0L);
        }

        /** 设置闸门：每次真实评分时先 countDown entered，再阻塞等待 release。 */
        public void arm(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        public void disarm() {
            this.entered = null;
            this.release = null;
        }

        @Override
        public double score(com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest r) {
            invocations.incrementAndGet();
            CountDownLatch e = entered;
            CountDownLatch rel = release;
            if (e != null) {
                e.countDown();
                try {
                    rel.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
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
        RecoveryPolicy policy = new RecoveryPolicy(circuitThreshold, openDurationMs,
                1.0d, openDurationMs);
        return build(policy, TimeSource.SYSTEM, cacheTtlMs, backendLatencyMs,
                defaultBackends(backendLatencyMs));
    }

    /**
     * 构建双版本灰度网关，自愈退避策略与时间源可注入（用于确定性冷却/退避测试）。
     */
    public static Harness build(RecoveryPolicy policy, TimeSource timeSource, long cacheTtlMs,
                                long backendLatencyMs) {
        return build(policy, timeSource, cacheTtlMs, backendLatencyMs,
                defaultBackends(backendLatencyMs));
    }

    /**
     * 全可控装配：使用自定义后端列表（数量/类型可裁剪，如只留一个 v1 后端验证退避）。
     */
    public static Harness build(RecoveryPolicy policy, TimeSource timeSource, long cacheTtlMs,
                                long backendLatencyMs, List<InMemoryInferenceBackend> backendList) {
        ConfigEpoch epoch = new ConfigEpoch();
        CanaryRouter router = new CanaryRouter(epoch);
        router.registerVersion(new ModelVersionSpec("v1", "ml-score", 3));
        router.registerVersion(new ModelVersionSpec("v2", "ml-score", 4));
        router.seedWeights("ml-score", Map.of("v1", 100, "v2", 0));

        BackendRegistry backends = new BackendRegistry();
        for (InMemoryInferenceBackend b : backendList) {
            backends.register(b);
        }

        CircuitBreakerRegistry circuits =
                new CircuitBreakerRegistry(policy, timeSource, 20);
        ScoringCache cache = new ScoringCache(cacheTtlMs);
        ScoringService service = new ScoringService(router, backends, circuits, cache, epoch);
        return new Harness(service, router, backends, circuits, cache, epoch);
    }

    private static List<InMemoryInferenceBackend> defaultBackends(long latencyMs) {
        return List.of(
                new CountingBackend("cpu-v1-1", BackendType.CPU, "v1", latencyMs),
                new CountingBackend("cpu-v1-2", BackendType.CPU, "v1", latencyMs),
                new CountingBackend("gpu-v2-1", BackendType.GPU, "v2", latencyMs),
                new CountingBackend("edge-v2-1", BackendType.EDGE, "v2", latencyMs));
    }

    public static CountingBackend counting(BackendRegistry registry, String backendId) {
        return (CountingBackend) registry.findById(backendId);
    }
}
