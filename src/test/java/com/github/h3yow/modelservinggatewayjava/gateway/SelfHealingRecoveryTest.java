package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendType;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.FailureMode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.Admission;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.BackendHealth;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitState;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.RecoveryPolicy;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.TimelineEvent;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.TimelineReason;
import com.github.h3yow.modelservinggatewayjava.gateway.support.ManualTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景四：后端自愈恢复。
 * 覆盖：冷却到点自动试探并恢复承载（失败计数清零）、试探失败逐级加长冷却且有上限、
 * 并发下同一后端同一时刻最多一个试探请求（其余走健康后端）。
 * 时间全部由 {@link ManualTimeSource} 驱动，结果确定性、无 sleep 抖动。
 */
class SelfHealingRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingRecoveryTest.class);

    private ScoreRequest req(String id, double... feats) {
        List<Double> fs = new java.util.ArrayList<>();
        for (double f : feats) {
            fs.add(f);
        }
        return new ScoreRequest(id, "v1", fs);
    }

    @Test
    @DisplayName("冷却到点自动试探成功→恢复承载且失败计数清零")
    void autoRecoversAfterCooldown_andResetsFailureCount() {
        ManualTimeSource clock = new ManualTimeSource();
        var policy = new RecoveryPolicy(2, 2_000L, 2.0d, 10_000L);
        var h = TestGatewayFactory.build(policy, clock, 60_000L, 0L);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-2")
                .setFailureMode(FailureMode.INTERNAL_ERROR);

        // 连续失败 -> 两个后端都隔离
        h.service().score(req("trip-1", 1, 1, 1));
        h.service().score(req("trip-2", 2, 2, 2));
        CircuitBreaker cb1 = h.circuits().forBackend("cpu-v1-1");
        assertEquals(CircuitState.OPEN, cb1.viewState());
        assertEquals(2, cb1.health().failureCount());

        // 冷却未到：继续隔离，请求被跳过
        clock.advanceMs(1_999L);
        assertEquals(Admission.DENY_ISOLATED, cb1.acquire());
        assertEquals(CircuitState.OPEN, cb1.viewState());

        // 冷却到点：解除故障，首个真实请求成为试探，成功 -> CLOSED 正常承载
        clock.advanceMs(1L);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        ScoreResult recovered = h.service().score(req("recover-1", 3, 3, 3));
        assertTrue(recovered.success(), "试探成功后必须恢复承载");
        assertEquals("cpu-v1-1", recovered.backendId());
        assertTrue(recovered.decisionReason().contains("RECOVERED_AFTER_PROBE"),
                "结果要能一眼看出本跳是恢复后承载，实际=" + recovered.decisionReason());
        assertEquals(CircuitState.CLOSED, cb1.viewState());
        assertEquals(0, cb1.health().failureCount(), "恢复后失败计数必须清零");
        assertEquals(0, cb1.health().backoffAttempt(), "恢复后退避轮次必须清零");
        assertEquals(0L, cb1.health().remainingCooldownMs());

        // 时间线：隔离 -> 试探 -> 恢复，三跳齐全
        List<TimelineEvent> timeline = cb1.health().timeline();
        assertEquals(TimelineReason.FAILURE_THRESHOLD_REACHED, timeline.get(0).reason());
        assertEquals(TimelineReason.COOLDOWN_EXPIRED_PROBE, timeline.get(1).reason());
        assertEquals(CircuitState.HALF_OPEN, timeline.get(1).to());
        assertEquals(TimelineReason.PROBE_SUCCEEDED, timeline.get(2).reason());
        assertEquals(CircuitState.CLOSED, timeline.get(2).to());
        log.info("[冷却后自动恢复] 后端=cpu-v1-1 时间线={} 结果判定={}",
                timeline.stream().map(e -> e.from() + "->" + e.to() + ":" + e.reason()).toList(),
                recovered.decisionReason());
    }

    @Test
    @DisplayName("试探失败后冷却逐级加长并受上限封顶、最终仍可恢复")
    void failedProbeLengthensCooldownProgressivelyUntilCap() {
        ManualTimeSource clock = new ManualTimeSource();
        var policy = new RecoveryPolicy(2, 1_000L, 2.0d, 4_000L);
        var h = TestGatewayFactory.build(policy, clock, 60_000L, 0L,
                List.of(new TestGatewayFactory.CountingBackend(
                        "cpu-v1-1", BackendType.CPU, "v1", 0L)));
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);

        // 首次隔离：冷却 1000ms
        h.service().score(req("t-1", 1, 1, 1));
        h.service().score(req("t-2", 2, 2, 2));
        assertEquals(CircuitState.OPEN, cb.viewState());
        assertEquals(1_000L, cb.health().cooldownMs());

        // 第 1 轮试探失败：冷却加长到 2000ms
        clock.advanceMs(1_000L);
        assertFalse(h.service().score(req("p-1", 3, 3, 3)).success());
        assertEquals(CircuitState.OPEN, cb.viewState());
        assertEquals(2_000L, cb.health().cooldownMs(), "试探失败后冷却应逐级加长");
        assertEquals(1, cb.health().backoffAttempt());

        // 只走 1000ms 还在冷却：不能试探（避免反复试探打垮不稳后端）
        clock.advanceMs(1_000L);
        assertEquals(Admission.DENY_ISOLATED, cb.acquire());

        // 第 2 轮试探失败：4000ms
        clock.advanceMs(1_000L);
        assertFalse(h.service().score(req("p-2", 4, 4, 4)).success());
        assertEquals(4_000L, cb.health().cooldownMs());

        // 第 3 轮试探失败：触顶 4000ms，不再继续加长
        clock.advanceMs(4_000L);
        assertFalse(h.service().score(req("p-3", 5, 5, 5)).success());
        assertEquals(4_000L, cb.health().cooldownMs(), "冷却必须受上限封顶");
        assertEquals(3, cb.health().backoffAttempt());

        // 后端恢复后，下一次试探仍可成功恢复（不会出现“再也恢复不了”）
        clock.advanceMs(4_000L);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        ScoreResult ok = h.service().score(req("p-ok", 6, 6, 6));
        assertTrue(ok.success());
        assertEquals(CircuitState.CLOSED, cb.viewState());
        assertEquals(0, cb.health().failureCount());
        log.info("[试探失败逐级退避] 冷却序列=1000->2000->4000(封顶)->恢复 时间线={}",
                cb.health().timeline().stream()
                        .map(e -> e.reason() + "(" + e.detail() + ")").toList());
    }

    @Test
    @DisplayName("并发下同一后端同一时刻仅一个试探、其余请求走健康后端")
    void onlyOneProbeInFlightPerBackend_othersGoToHealthyBackend() throws Exception {
        ManualTimeSource clock = new ManualTimeSource();
        var policy = new RecoveryPolicy(2, 1_000L, 2.0d, 10_000L);
        var flaky = new TestGatewayFactory.BlockingCountingBackend("cpu-v1-1",
                BackendType.CPU, "v1");
        var healthy = new TestGatewayFactory.CountingBackend("cpu-v1-2",
                BackendType.CPU, "v1", 0L);
        var h = TestGatewayFactory.build(policy, clock, 60_000L, 0L, List.of(flaky, healthy));
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");

        // 打满阈值隔离 cpu-v1-1
        flaky.setFailureMode(FailureMode.INTERNAL_ERROR);
        h.service().score(req("t-1", 1, 1, 1));
        h.service().score(req("t-2", 2, 2, 2));
        assertEquals(CircuitState.OPEN, cb.viewState());

        // 冷却到点：让试探请求进入后端后阻塞，保持“试探在飞”
        clock.advanceMs(1_000L);
        flaky.setFailureMode(FailureMode.NONE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        flaky.arm(entered, release);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger okCount = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final double seed = 10.0 + i; // 每线程不同特征，避免缓存互相合并
            pool.submit(() -> {
                try {
                    start.await();
                    ScoreResult r = h.service().score(req("cc-" + Thread.currentThread().getId(),
                            seed, seed, seed));
                    if (r.success()) {
                        okCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "试探请求应进入被隔离后端");
        // 给其余请求一点时间落到健康后端
        Thread.sleep(200);

        BackendHealth during = cb.health();
        assertEquals(CircuitState.HALF_OPEN, during.state(), "试探在飞期间应为 HALF_OPEN");
        assertTrue(during.probeInFlight(), "观测应显示试探在飞");
        // 隔离前 t-1/t-2 各真实调用过 flaky 一次（2 次）；冷却后只新增唯一 1 个试探
        assertEquals(3, flaky.invocations.get(), "同一时刻同一后端只能有一个试探请求");
        // t-1/t-2 已降级到 healthy 2 次，并发其余 15 个请求也必须全部走 healthy
        assertEquals(2 + threads - 1, healthy.invocations.get(),
                "其余请求必须继续走健康后端，实际=" + healthy.invocations.get());
        log.info("[并发唯一试探] 试探在飞: flaky调用={} healthy承载={} 其余请求未被阻塞",
                flaky.invocations.get(), healthy.invocations.get());

        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "全部请求应完成");
        pool.shutdown();

        assertEquals(threads, okCount.get(), "所有请求都应成功（1 试探恢复 + 15 健康承载）");
        assertEquals(3, flaky.invocations.get(), "被隔离后端冷却后整个过程只接到 1 个试探请求");
        assertEquals(CircuitState.CLOSED, cb.viewState(), "试探成功后恢复 CLOSED");
        log.info("[并发唯一试探] 试探放行后 flaky 恢复 CLOSED，总成功={}", okCount.get());
    }
}
