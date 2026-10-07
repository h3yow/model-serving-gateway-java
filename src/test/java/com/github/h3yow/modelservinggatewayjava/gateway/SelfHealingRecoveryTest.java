package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.FailureMode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.DecisionReason;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.BackendHealth;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitState;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.RecoveryPolicy;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.TimelineEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景四：后端自愈恢复与只读观测。
 * 覆盖：冷却后自动恢复并清零失败计数；试探失败冷却逐级加长且有上限；
 * 并发下同一后端同一时刻最多一个试探请求；只读查询不推进恢复、不计入统计、
 * 高频并发查询结果一致；状态时间线有界、按序、写满覆盖最旧。
 * 全部使用手动时钟，确定性无 flaky。
 */
class SelfHealingRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingRecoveryTest.class);

    /** 每条请求使用不同特征，避免命中缓存跳过真实后端调用。 */
    private ScoreRequest req(String id, int salt) {
        return new ScoreRequest(id, "v1", List.of(1.0, 2.0, 1000.0 + salt));
    }

    @Test
    void recoversAutomaticallyAfterCooldownAndClearsFailureCount() {
        TestGatewayFactory.ManualClock clock = new TestGatewayFactory.ManualClock(1_000_000);
        var h = TestGatewayFactory.build(new RecoveryPolicy(2, 1000, 2.0, 10_000),
                clock, 60_000, 0);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);

        // 连续失败 2 次（每次自动降级到 cpu-v1-2），达到阈值 -> 隔离
        h.service().score(req("f-0", 0));
        h.service().score(req("f-1", 1));
        BackendHealth isolated = h.circuits().healthOf("cpu-v1-1");
        assertEquals(CircuitState.OPEN, isolated.state());
        assertEquals(2, isolated.failureCount());
        assertEquals(1000, isolated.cooldownMs());
        assertEquals(1000, isolated.remainingCooldownMs(), "隔离刚开始，剩余冷却应等于完整冷却");
        log.info("[自愈-隔离] 状态={} 失败计数={} 冷却={}ms 剩余={}ms",
                isolated.state(), isolated.failureCount(),
                isolated.cooldownMs(), isolated.remainingCooldownMs());

        // 隔离期间：请求跳过被隔离后端，由健康后端承载，结果里能看出「被隔离跳过」
        ScoreResult during = h.service().score(req("during-open", 2));
        assertTrue(during.success());
        assertEquals("cpu-v1-2", during.backendId());
        assertTrue(during.decisionReason().contains("cpu-v1-1:" + DecisionReason.CIRCUIT_OPEN_SKIPPED),
                "隔离期间应标注跳过，实际=" + during.decisionReason());
        log.info("[自愈-隔离期跳过] request={} 后端={} 判定依据={}",
                during.requestId(), during.backendId(), during.decisionReason());

        // 冷却到点：自动放试探；后端已恢复 -> 试探成功 -> 恢复正常承载、失败计数清零
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        clock.advance(1000);
        ScoreResult recovered = h.service().score(req("recover-1", 3));
        assertTrue(recovered.success());
        assertEquals("cpu-v1-1", recovered.backendId(), "试探请求应打到刚恢复的后端");
        assertTrue(recovered.decisionReason().contains(DecisionReason.RECOVERED_SERVING),
                "恢复后首跳应标注 RECOVERED_SERVING，实际=" + recovered.decisionReason());
        BackendHealth healed = h.circuits().healthOf("cpu-v1-1");
        assertEquals(CircuitState.CLOSED, healed.state());
        assertEquals(0, healed.failureCount(), "恢复后失败计数必须清零");
        assertEquals(0, healed.backoffAttempt(), "恢复后退避轮次必须清零");
        log.info("[自愈-恢复] request={} 后端={} 判定依据={} 状态={} 失败计数={}",
                recovered.requestId(), recovered.backendId(), recovered.decisionReason(),
                healed.state(), healed.failureCount());

        // 恢复后的后续请求正常承载（不再是试探跳）
        ScoreResult after = h.service().score(req("after-recover", 4));
        assertTrue(after.success());
        assertEquals("cpu-v1-1", after.backendId());
        assertFalse(after.decisionReason().contains(DecisionReason.RECOVERED_SERVING));

        // 时间线记录了三段关键迁移：CLOSED->OPEN -> OPEN->HALF_OPEN -> HALF_OPEN->CLOSED
        List<TimelineEvent> tl = healed.timeline();
        assertTrue(tl.size() >= 3, "时间线应记录关键迁移，实际=" + tl);
        assertEquals(CircuitState.CLOSED, tl.get(tl.size() - 3).from());
        assertEquals(CircuitState.OPEN, tl.get(tl.size() - 3).to());
        assertEquals(CircuitState.OPEN, tl.get(tl.size() - 2).from());
        assertEquals(CircuitState.HALF_OPEN, tl.get(tl.size() - 2).to());
        assertEquals(CircuitState.HALF_OPEN, tl.get(tl.size() - 1).from());
        assertEquals(CircuitState.CLOSED, tl.get(tl.size() - 1).to());
        log.info("[自愈-时间线] {}", tl);
    }

    @Test
    void probeFailureEscalatesCooldownWithCap() {
        TestGatewayFactory.ManualClock clock = new TestGatewayFactory.ManualClock(1_000_000);
        // 首次冷却 1s，每次试探失败乘 2，上限 4s
        var h = TestGatewayFactory.build(new RecoveryPolicy(1, 1000, 2.0, 4000),
                clock, 60_000, 0);
        var faulty = TestGatewayFactory.counting(h.backends(), "cpu-v1-1");
        faulty.setFailureMode(FailureMode.INTERNAL_ERROR);

        // 第 1 次隔离：冷却 1000ms
        h.service().score(req("trip", 0));
        assertEquals(1000, h.circuits().healthOf("cpu-v1-1").cooldownMs());

        // 冷却还差 1ms：仍被隔离跳过，不会提前试探
        clock.advance(999);
        ScoreResult early = h.service().score(req("early", 1));
        assertTrue(early.success());
        assertEquals("cpu-v1-2", early.backendId());
        assertEquals(1, faulty.invocations.get(), "冷却未到期不得放行试探");
        assertEquals(1, h.circuits().healthOf("cpu-v1-1").remainingCooldownMs(),
                "冷却 1000ms 已推进 999ms，应只剩 1ms");

        // 冷却到点 -> 试探失败 -> 冷却升到 2000ms
        clock.advance(1);
        h.service().score(req("probe-fail-1", 2));
        BackendHealth after1 = h.circuits().healthOf("cpu-v1-1");
        assertEquals(CircuitState.OPEN, after1.state());
        assertEquals(2000, after1.cooldownMs(), "第一次试探失败后冷却应加倍");
        assertEquals(1, after1.backoffAttempt());
        log.info("[退避-第1级] 冷却={}ms 轮次={} 剩余={}ms",
                after1.cooldownMs(), after1.backoffAttempt(), after1.remainingCooldownMs());

        // 第 2 次试探失败 -> 4000ms
        clock.advance(2000);
        h.service().score(req("probe-fail-2", 3));
        BackendHealth after2 = h.circuits().healthOf("cpu-v1-1");
        assertEquals(4000, after2.cooldownMs());
        assertEquals(2, after2.backoffAttempt());
        log.info("[退避-第2级] 冷却={}ms 轮次={}", after2.cooldownMs(), after2.backoffAttempt());

        // 第 3 次试探失败 -> 8000ms 超上限，封顶 4000ms
        clock.advance(4000);
        h.service().score(req("probe-fail-3", 4));
        BackendHealth after3 = h.circuits().healthOf("cpu-v1-1");
        assertEquals(4000, after3.cooldownMs(), "冷却加长必须封顶在 maxCooldownMs");
        assertEquals(3, after3.backoffAttempt());
        log.info("[退避-封顶] 冷却={}ms 轮次={}（上限生效）", after3.cooldownMs(), after3.backoffAttempt());

        // 冷却结束仍能恢复：后端修复后下一次试探成功，退避轮次清零
        faulty.setFailureMode(FailureMode.NONE);
        clock.advance(4000);
        ScoreResult recovered = h.service().score(req("finally-recover", 5));
        assertTrue(recovered.success());
        assertEquals("cpu-v1-1", recovered.backendId());
        assertTrue(recovered.decisionReason().contains(DecisionReason.RECOVERED_SERVING));
        assertEquals(CircuitState.CLOSED, h.circuits().healthOf("cpu-v1-1").state());
        assertEquals(0, h.circuits().healthOf("cpu-v1-1").backoffAttempt());
        log.info("[退避-最终恢复] 判定依据={}", recovered.decisionReason());
    }

    @Test
    void atMostOneProbeUnderConcurrency() throws Exception {
        // 单元级：并发 tryAcquire 只有一个能抢到试探权（完全确定性）
        TestGatewayFactory.ManualClock clock = new TestGatewayFactory.ManualClock(1_000_000);
        CircuitBreaker cb = new CircuitBreaker("b1",
                new RecoveryPolicy(1, 1000, 2.0, 10_000), clock, 8);
        cb.onFailure();
        assertEquals(CircuitState.OPEN, cb.state());
        clock.advance(1000);

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    CircuitBreaker.Permit p = cb.tryAcquire();
                    if (p == CircuitBreaker.Permit.PROBE) {
                        probes.incrementAndGet();
                    } else if (p == CircuitBreaker.Permit.REJECTED) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, probes.get(), "同一时刻最多一个试探请求");
        assertEquals(threads - 1, rejected.get(), "其余并发请求必须被拒绝走健康后端");
        log.info("[单试探-单元级] 并发={} 抢到试探={} 被拒绝={}", threads, probes.get(), rejected.get());

        // 服务级：试探在飞期间，并发请求全部走健康后端且都成功
        TestGatewayFactory.ManualClock clock2 = new TestGatewayFactory.ManualClock(1_000_000);
        var h = TestGatewayFactory.build(new RecoveryPolicy(1, 1000, 1.0, 1000),
                clock2, 60_000, 400); // 后端 400ms 延迟，保证试探在飞窗口
        var faulty = TestGatewayFactory.counting(h.backends(), "cpu-v1-1");
        faulty.setFailureMode(FailureMode.INTERNAL_ERROR);
        h.service().score(req("trip", 0)); // 隔离 cpu-v1-1
        assertEquals(CircuitState.OPEN, h.circuits().healthOf("cpu-v1-1").state());
        clock2.advance(1000);
        faulty.setFailureMode(FailureMode.NONE); // 后端已恢复，但试探只能有一个
        int invocationsBeforeProbe = faulty.invocations.get(); // 触发隔离时已调用 1 次

        int concurrent = 8;
        CyclicBarrier start = new CyclicBarrier(concurrent);
        ExecutorService pool2 = Executors.newFixedThreadPool(concurrent);
        ConcurrentLinkedQueue<ScoreResult> results = new ConcurrentLinkedQueue<>();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < concurrent; i++) {
            final int salt = i;
            fs.add(pool2.submit(() -> {
                try {
                    start.await();
                    results.add(h.service().score(req("c-" + salt, 100 + salt)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Future<?> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool2.shutdown();

        assertEquals(concurrent, results.size());
        assertTrue(results.stream().allMatch(ScoreResult::success),
                "并发期间所有请求都应由健康后端或试探成功承载");
        assertEquals(invocationsBeforeProbe + 1, faulty.invocations.get(),
                "试探在飞期间同后端最多一个试探请求，实际=" + faulty.invocations.get());
        long recoveredCount = results.stream()
                .filter(r -> r.decisionReason().contains(DecisionReason.RECOVERED_SERVING))
                .count();
        long fallbackCount = results.stream()
                .filter(r -> "cpu-v1-2".equals(r.backendId()))
                .count();
        assertEquals(1, recoveredCount, "恰好一个请求是恢复后首跳");
        assertEquals(concurrent - 1, fallbackCount, "其余请求走健康后端");
        assertEquals(CircuitState.CLOSED, h.circuits().healthOf("cpu-v1-1").state());
        log.info("[单试探-服务级] 并发={} 试探成功={} 走健康后端={} cpu-v1-1实际调用={}",
                concurrent, recoveredCount, fallbackCount, faulty.invocations.get());
    }

    @Test
    void readOnlyQueriesDoNotAffectRecoveryProgress() throws Exception {
        TestGatewayFactory.ManualClock clock = new TestGatewayFactory.ManualClock(1_000_000);
        var h = TestGatewayFactory.build(new RecoveryPolicy(2, 10_000, 2.0, 60_000),
                clock, 60_000, 0);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);

        h.service().score(req("f-0", 0));
        h.service().score(req("f-1", 1));
        BackendHealth before = h.circuits().healthOf("cpu-v1-1");
        assertEquals(CircuitState.OPEN, before.state());
        assertEquals(2, before.failureCount());
        Map<String, Map<String, Long>> trafficBefore = h.router().trafficCounts();
        int cacheSizeBefore = h.cache().size();
        int timelineSizeBefore = before.timeline().size();

        // 高频并发只读查询：健康快照 / 状态表 / 单后端健康 / 流量观测
        int threads = 8, iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < iterations; i++) {
                    h.circuits().healthSnapshot();
                    h.circuits().snapshotStates();
                    h.circuits().healthOf("cpu-v1-1");
                    h.router().trafficCounts();
                }
                done.countDown();
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        BackendHealth after = h.circuits().healthOf("cpu-v1-1");
        assertEquals(CircuitState.OPEN, after.state(), "只读查询不得推进状态迁移");
        assertEquals(2, after.failureCount(), "只读查询不得计入失败统计");
        assertEquals(0, after.backoffAttempt());
        assertEquals(10_000, after.remainingCooldownMs(), "只读查询不得消耗冷却时间");
        assertEquals(timelineSizeBefore, after.timeline().size(), "只读查询不得写时间线");
        assertEquals(trafficBefore, h.router().trafficCounts(), "只读查询不得计入流量统计");
        assertEquals(cacheSizeBefore, h.cache().size(), "只读查询不得改变缓存");
        log.info("[只读-无副作用] 并发查询={}x{} 后状态={} 失败计数={} 剩余冷却={}ms",
                threads, iterations, after.state(), after.failureCount(),
                after.remainingCooldownMs());

        // 同一时刻反复查询结果一致
        BackendHealth s1 = h.circuits().healthOf("cpu-v1-1");
        BackendHealth s2 = h.circuits().healthOf("cpu-v1-1");
        assertEquals(s1, s2, "同一时刻反复只读查询结果必须一致");

        // 冷却时间到但没有任何请求：查询不会自动「恢复」，仍报告隔离（剩余 0）
        clock.advance(10_000);
        BackendHealth expired = h.circuits().healthOf("cpu-v1-1");
        assertEquals(CircuitState.OPEN, expired.state(), "只读查询不得把 OPEN 推进为 HALF_OPEN");
        assertEquals(0, expired.remainingCooldownMs());
        BackendHealth expired2 = h.circuits().healthOf("cpu-v1-1");
        assertEquals(expired, expired2, "冷却到期后反复查询结果仍一致");

        // 冷却结束不会「怎么也恢复不了」：真实请求一到即放试探并成功恢复
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        ScoreResult recovered = h.service().score(req("recover", 2));
        assertTrue(recovered.success());
        assertEquals("cpu-v1-1", recovered.backendId());
        assertTrue(recovered.decisionReason().contains(DecisionReason.RECOVERED_SERVING));
        assertEquals(CircuitState.CLOSED, h.circuits().healthOf("cpu-v1-1").state());
        log.info("[只读-恢复如常] 高频查询后恢复不受影响 判定依据={}", recovered.decisionReason());
    }

    @Test
    void timelineIsBoundedOrderedAndOverwritesOldest() {
        TestGatewayFactory.ManualClock clock = new TestGatewayFactory.ManualClock(1_000_000);
        int capacity = 4;
        CircuitBreaker cb = new CircuitBreaker("b-tl",
                new RecoveryPolicy(1, 100, 2.0, 100_000), clock, capacity);

        // 制造远超容量的状态迁移：每轮 OPEN -> HALF_OPEN -> OPEN 约 3 条
        for (int round = 0; round < 10; round++) {
            cb.onFailure();                       // CLOSED -> OPEN（首轮）或试探失败再 OPEN
            clock.advance(100_000);               // 越过任意冷却
            assertEquals(CircuitBreaker.Permit.PROBE, cb.tryAcquire()); // OPEN -> HALF_OPEN
            if (round == 9) {
                cb.onSuccess();                   // 最后一轮试探成功 -> CLOSED
            }
        }

        List<TimelineEvent> tl = cb.snapshot().timeline();
        assertEquals(capacity, tl.size(), "时间线写满后条数必须封顶在容量");
        for (int i = 1; i < tl.size(); i++) {
            assertTrue(tl.get(i).seq() > tl.get(i - 1).seq(), "序号必须单调递增");
            assertTrue(tl.get(i).timestampMs() >= tl.get(i - 1).timestampMs(),
                    "快照必须按时间正序");
        }
        for (TimelineEvent e : tl) {
            assertNotNull(e.from());
            assertNotNull(e.to());
            assertNotNull(e.reason());
        }
        // 最新一条是最后一轮的试探成功（HALF_OPEN -> CLOSED），最旧的已被覆盖
        TimelineEvent last = tl.get(tl.size() - 1);
        assertEquals(CircuitState.HALF_OPEN, last.from());
        assertEquals(CircuitState.CLOSED, last.to());
        assertEquals("试探成功", last.reason());
        log.info("[时间线-有界] 容量={} 实际={} 序号区间=[{}..{}] 最新={}->{}/{}",
                capacity, tl.size(), tl.get(0).seq(), last.seq(),
                last.from(), last.to(), last.reason());
    }
}
