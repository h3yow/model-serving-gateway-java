package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.FailureMode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.AdmissionResult;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.BackendHealthView;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreakerConfig;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitState;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.StateTransitionEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景四：后端自愈与安全观测。
 * 覆盖：冷却后自动恢复、试探失败后冷却逐级退避（乘数/上限可配）、
 * 并发下同一后端同时只有一个试探请求、只读观测不推进也不打断恢复、
 * 恢复后新请求稳定落回该后端且结果可区分、状态时间线有界且在并发下不乱序不丢失。
 */
class SelfHealingTest {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingTest.class);

    private ScoreRequest req(String id) {
        return new ScoreRequest(id, "v1", List.of(1.0, 2.0, 3.0));
    }

    /** 让某后端连续失败直到 OPEN（threshold 很小，直接打 2 轮即可）。 */
    private void trip(String backend, TestGatewayFactory.Harness h) {
        TestGatewayFactory.counting(h.backends(), backend)
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        for (int i = 0; i < 4; i++) {
            h.service().score(new ScoreRequest("trip-" + backend + "-" + i, "v1",
                    List.of(9.0, 8.0, (double) i)));
        }
        assertEquals(CircuitState.OPEN, h.circuits().forBackend(backend).state());
        TestGatewayFactory.counting(h.backends(), backend).setFailureMode(FailureMode.NONE);
    }

    @Test
    void autoRecoversAfterCooldownAndStablyServesAgain() throws Exception {
        var h = TestGatewayFactory.build(
                new CircuitBreakerConfig(2, 150, 2.0, 5_000, 20), 60_000, 0);
        trip("cpu-v1-1", h);
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");

        // 冷却期内：观测为 ISOLATED，恢复未发生
        BackendHealthView isolating = cb.view();
        assertEquals(CircuitBreaker.PHASE_ISOLATED, isolating.phase());
        assertTrue(isolating.remainingIsolationMs() > 0);

        // 冷却结束、真实请求到达前：观测可报告“冷却已到”，但状态仍是 OPEN（观测不驱动恢复）
        Thread.sleep(220);
        BackendHealthView due = cb.view();
        assertEquals(CircuitState.OPEN, due.state(), "只读查询不得推进状态");
        assertEquals(CircuitBreaker.PHASE_ISOLATED_COOLDOWN_DUE, due.phase());
        assertTrue(due.probeEligible());
        log.info("[自动恢复-冷却已到] backend=cpu-v1-1 phase={} state={} remaining={}ms",
                due.phase(), due.state(), due.remainingIsolationMs());

        // 第一个真实请求自动成为恢复试探并成功
        ScoreResult recovered = h.service().score(req("recover-probe"));
        assertTrue(recovered.success(), "试探成功应恢复承载");
        assertEquals("cpu-v1-1", recovered.backendId());
        assertTrue(recovered.decisionReason().contains("RECOVERY_PROBE_SUCCEEDED"),
                "结果必须能看出是恢复试探成功后承载，实际=" + recovered.decisionReason());
        assertEquals(CircuitState.CLOSED, cb.state());
        log.info("[自动恢复-成功] request={} backend={} 判定依据={}",
                recovered.requestId(), recovered.backendId(), recovered.decisionReason());

        // 恢复后新请求稳定落回该后端（连续多次），且已返回的旧结果不被改写
        double probeValue = recovered.result();
        for (int i = 0; i < 5; i++) {
            ScoreResult r = h.service().score(req("post-" + i));
            assertTrue(r.success());
            assertEquals("cpu-v1-1", r.backendId(), "恢复后新请求必须稳定落回该后端");
            assertEquals(probeValue, r.result(), 0.0);
        }
        assertEquals(CircuitState.CLOSED, cb.state());
        log.info("[自动恢复-稳定承载] 恢复后连续 5 个新请求均落回 cpu-v1-1");
    }

    @Test
    void failedProbeExtendsCooldownExponentiallyUpToCap() throws Exception {
        long base = 100;
        double mult = 2.0;
        long max = 400;
        var h = TestGatewayFactory.build(
                new CircuitBreakerConfig(2, base, mult, max, 20), 60_000, 0);
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");
        // 两个后端都故障，使每个请求都能轮到对 cpu-v1-1 的尝试（降级链最后仍失败）
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.INTERNAL_ERROR);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-2").setFailureMode(FailureMode.INTERNAL_ERROR);
        for (int i = 0; i < 3; i++) {
            h.service().score(new ScoreRequest("trip-" + i, "v1", List.of(1.0, 1.0, (double) i)));
        }
        assertEquals(CircuitState.OPEN, cb.state());
        assertEquals(base, cb.currentBackoffMs());

        // 第一次试探失败 -> 冷却退避到 200
        Thread.sleep(base + 60);
        h.service().score(new ScoreRequest("probe1", "v1", List.of(2.0, 2.0, 2.0)));
        assertEquals(CircuitState.OPEN, cb.state());
        assertEquals(200, cb.currentBackoffMs(), "首次试探失败后冷却应按乘数加长到 200");
        log.info("[退避] 试探#1 失败 下次冷却={}ms (预期200)", cb.currentBackoffMs());

        // 冷却未到，试探不应发生
        h.service().score(new ScoreRequest("early", "v1", List.of(3.0, 3.0, 3.0)));
        assertEquals(CircuitState.OPEN, cb.state(), "冷却未结束不得提前试探");

        // 第二次试探失败 -> 400（达到上限）
        Thread.sleep(200 + 60);
        h.service().score(new ScoreRequest("probe2", "v1", List.of(4.0, 4.0, 4.0)));
        assertEquals(CircuitState.OPEN, cb.state());
        assertEquals(400, cb.currentBackoffMs());

        // 第三次试探失败 -> 仍被上限钳制在 400，不无限增长
        Thread.sleep(400 + 60);
        h.service().score(new ScoreRequest("probe3", "v1", List.of(5.0, 5.0, 5.0)));
        assertEquals(CircuitState.OPEN, cb.state());
        assertEquals(max, cb.currentBackoffMs(), "退避必须受上限钳制");
        log.info("[退避上限] 试探#3 失败 下次冷却={}ms (被上限钳制)", cb.currentBackoffMs());

        // 退避序列完整记录在时间线上
        List<Long> backoffs = cb.timeline().stream()
                .filter(e -> CircuitBreaker.REASON_RECOVERY_PROBE_FAILED.equals(e.reason()))
                .map(StateTransitionEvent::backoffMs).toList();
        assertEquals(List.of(200L, 400L, 400L), backoffs);

        // 试探成功后冷却回到初始值
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-2").setFailureMode(FailureMode.NONE);
        Thread.sleep(400 + 60);
        ScoreResult ok = h.service().score(req("final-recover"));
        assertTrue(ok.success());
        assertEquals(CircuitState.CLOSED, cb.state());
        assertEquals(base, cb.currentBackoffMs(), "恢复后退避应复位为初始冷却");
        log.info("[退避复位] 试探成功后冷却复位为 {}ms", cb.currentBackoffMs());
    }

    @Test
    void onlyOneProbeRequestInFlightForSameBackendConcurrently() throws Exception {
        long backoff = 600;
        var h = TestGatewayFactory.build(
                new CircuitBreakerConfig(2, backoff, 2.0, 60_000, 20), 60_000, 0);
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");
        // 把 cpu-v1-1 打成 OPEN；cpu-v1-2 保持健康，承接被跳过的流量
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.INTERNAL_ERROR);
        for (int i = 0; i < 3; i++) {
            h.service().score(new ScoreRequest("trip-" + i, "v1", List.of(1.0, 2.0, (double) i)));
        }
        assertEquals(CircuitState.OPEN, cb.state());

        // 给 cpu-v1-1 加可控延迟闸门：恢复试探请求会阻塞在后端里，制造并发窗口
        var probeBackend = TestGatewayFactory.counting(h.backends(), "cpu-v1-1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrentInBackend = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        probeBackend.setGate(() -> {
            entered.countDown();
            int n = concurrentInBackend.incrementAndGet();
            maxConcurrent.accumulateAndGet(n, Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentInBackend.decrementAndGet();
            }
        });
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);

        // 冷却过后：一个独立线程发起真实请求，成为唯一在执行的恢复试探
        Thread.sleep(backoff + 150);
        ExecutorService probePool = Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<ScoreResult> probeFuture =
                probePool.submit(() -> h.service().score(new ScoreRequest(
                        "the-probe", "v1", List.of(6.0, 6.0, 6.0))));
        assertTrue(entered.await(2, TimeUnit.SECONDS), "试探请求应已进入后端并被闸门挂住");
        assertEquals(CircuitState.HALF_OPEN, cb.state(), "试探执行中权威状态应为 HALF_OPEN");
        assertEquals(CircuitBreaker.PHASE_PROBING, cb.view().phase());
        assertTrue(cb.view().probeInFlight());

        // 熔断器层面 32 个并发准入：零个能再拿到试探权，全部被判定为“试探进行中”
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger rejectedInFlight = new AtomicInteger();
        AtomicInteger rejectedOther = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AdmissionResult r = cb.admit();
                    if (r.allowed() && r.probe()) {
                        probes.incrementAndGet();
                    } else if (!r.allowed()
                            && AdmissionResult.SKIP_PROBE_IN_FLIGHT.equals(r.skipReason())) {
                        rejectedInFlight.incrementAndGet();
                    } else {
                        rejectedOther.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, probes.get(), "试探进行中不得再放行任何试探请求");
        assertEquals(threads, rejectedInFlight.get(), "其余并发必须被判定为试探进行中");
        assertEquals(0, rejectedOther.get());
        log.info("[并发单试探] HALF_OPEN 期间 {} 个并发准入：新增试探=0 试探进行中跳过={}",
                threads, rejectedInFlight.get());

        // 端到端：16 个真实评分请求并发到达，全部降级到健康后端，绝不并发打到试探后端
        int callers = 16;
        ExecutorService callPool = Executors.newFixedThreadPool(callers);
        CountDownLatch callStart = new CountDownLatch(1);
        List<ScoreResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < callers; i++) {
            int idx = i;
            callPool.submit(() -> {
                try {
                    callStart.await();
                    // 不同特征向量：避免被缓存并发去重合并，确保每个请求都真实经过熔断降级链
                    results.add(h.service().score(new ScoreRequest(
                            "during-probe-" + idx, "v1", List.of(7.0, 7.0, (double) idx))));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        callStart.countDown();
        callPool.shutdown();
        assertTrue(callPool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(callers, results.size());
        for (ScoreResult r : results) {
            assertTrue(r.success(), "其它流量应被健康后端承接");
            assertEquals("cpu-v1-2", r.backendId(), "试探进行中不得并发打到 cpu-v1-1");
            assertTrue(r.decisionReason().contains("RECOVERY_PROBE_IN_FLIGHT_SKIPPED"),
                    "结果必须能看出该后端因试探进行中被跳过，实际=" + r.decisionReason());
        }
        assertEquals(1, maxConcurrent.get(), "后端内试探并发度必须为 1，实际=" + maxConcurrent.get());
        log.info("[并发单试探-端到端] 试探期间 {} 个真实请求全部由 cpu-v1-2 健康承接，"
                + "cpu-v1-1 后端内最大并发=1，判定依据含 RECOVERY_PROBE_IN_FLIGHT_SKIPPED", callers);

        // 释放试探：唯一试探成功 -> CLOSED，后端重新承载流量
        release.countDown();
        ScoreResult probeResult = probeFuture.get(5, TimeUnit.SECONDS);
        probePool.shutdown();
        assertTrue(probeResult.success());
        assertEquals("cpu-v1-1", probeResult.backendId());
        assertTrue(probeResult.decisionReason().contains("RECOVERY_PROBE_SUCCEEDED"));
        assertEquals(CircuitState.CLOSED, cb.state());
        log.info("[并发单试探-收尾] 唯一试探成功，cpu-v1-1 恢复 CLOSED 正常承载");
    }

    @Test
    void readOnlyObservationNeverAdvancesOrInterruptsRecovery() throws Exception {
        long backoff = 250;
        var h = TestGatewayFactory.build(
                new CircuitBreakerConfig(2, backoff, 2.0, 5_000, 20), 60_000, 0);
        trip("cpu-v1-1", h);
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");
        int timelineBefore = cb.view().timeline().size();

        // 冷却中高频、并发只读查询：状态、时间线、失败计数、恢复进度均不得被观测改变
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                while (stop.getCount() > 0) {
                    BackendHealthView v = cb.view();
                    h.circuits().snapshotHealth();
                    h.circuits().snapshotStates();
                    h.circuits().healthOf("cpu-v1-1");
                    h.router().trafficCounts();
                    h.router().weightSnapshot();
                    assertEquals(CircuitState.OPEN, v.state(), "查询期间不得被观测推进");
                    checks.incrementAndGet();
                }
            });
        }
        Thread.sleep(backoff + 150); // 覆盖整个冷却窗口
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(CircuitState.OPEN, cb.state(), "冷却过后只读查询仍不得自动恢复");
        assertEquals(CircuitBreaker.PHASE_ISOLATED_COOLDOWN_DUE, cb.view().phase());
        assertEquals(timelineBefore, cb.view().timeline().size(), "只读查询不得写时间线");
        log.info("[只读观测] 冷却窗口内 {} 次并发只读查询，状态仍=OPEN/COOLDOWN_DUE，时间线={}条未被观测改变",
                checks.get(), timelineBefore);

        // 恢复必须由真实请求触发：首个真实请求即抢到试探并成功
        ScoreResult recovered = h.service().score(req("real-recover"));
        assertTrue(recovered.success());
        assertEquals(CircuitState.CLOSED, cb.state());
        assertEquals(timelineBefore + 2, cb.view().timeline().size(),
                "真实恢复应恰好新增 PROBE_GRANTED 与 PROBE_SUCCEEDED 两条");
        log.info("[只读观测] 恢复仅由真实请求触发，时间线新增2条: {}",
                cb.view().timeline().subList(timelineBefore, cb.view().timeline().size()).stream()
                        .map(StateTransitionEvent::reason).toList());

        // 观测不计入真实流量统计：查询前后 v1 计数差仅来自上面的真实评分请求
        long trafficAfter = h.router().trafficCounts().get("ml-score").get("v1");
        for (int i = 0; i < 100; i++) {
            h.circuits().snapshotHealth();
            h.router().trafficCounts();
        }
        assertEquals(trafficAfter, h.router().trafficCounts().get("ml-score").get("v1"),
                "只读观测不得计入真实流量统计");
    }

    @Test
    void timelineIsBoundedOrderedAndSurvivesConcurrentTransitions() throws Exception {
        int cap = 5;
        CircuitBreaker cb = new CircuitBreaker(
                "x", new CircuitBreakerConfig(1, 1, 1.0, 1, cap));

        // 一个只读线程持续高频查询：验证与状态迁移并发时时间线不乱序、不被查询写入
        ExecutorService readers = Executors.newFixedThreadPool(4);
        CountDownLatch stopReaders = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            readers.submit(() -> {
                while (stopReaders.getCount() > 0) {
                    List<StateTransitionEvent> v = cb.timeline();
                    for (int j = 1; j < v.size(); j++) {
                        assertTrue(v.get(j).timestampMs() >= v.get(j - 1).timestampMs());
                    }
                }
            });
        }

        // 主线程串行制造远多于容量的状态迁移：OPEN->（冷却）HALF_OPEN->（试探失败）OPEN 循环
        for (int round = 0; round < 30; round++) {
            cb.onFailure(); // CLOSED->OPEN（阈值1）；OPEN 下仅累加计数
            if (cb.state() == CircuitState.OPEN) {
                Thread.sleep(2); // 超过 1ms 冷却
                AdmissionResult r = cb.admit(); // OPEN->HALF_OPEN
                assertTrue(r.allowed() && r.probe());
                cb.onFailure(); // 试探失败 HALF_OPEN->OPEN
            }
            assertTrue(cb.timeline().size() <= cap, "任何时刻时间线都不得超过容量");
        }
        stopReaders.countDown();
        readers.shutdown();
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS));

        List<StateTransitionEvent> snap = cb.timeline();
        assertEquals(cap, snap.size(), "时间线写满后必须恰好保留容量上限条（覆盖最旧）");
        for (int i = 1; i < snap.size(); i++) {
            assertTrue(snap.get(i).timestampMs() >= snap.get(i - 1).timestampMs(),
                    "时间线必须按时间有序");
            assertNotNull(snap.get(i).reason());
            assertNotNull(snap.get(i).from());
            assertNotNull(snap.get(i).to());
        }
        log.info("[时间线有界有序] 30 轮迁移+4 个并发只读线程后保留最近{}条（容量{}），原因序列={}",
                snap.size(), cap, snap.stream().map(StateTransitionEvent::reason).toList());
    }
}
