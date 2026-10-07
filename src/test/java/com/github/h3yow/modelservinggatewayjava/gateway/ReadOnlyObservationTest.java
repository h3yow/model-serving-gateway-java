package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.FailureMode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.BackendHealth;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitState;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.RecoveryPolicy;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.StateTimeline;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.TimelineEvent;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.TimelineReason;
import com.github.h3yow.modelservinggatewayjava.gateway.support.ManualTimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景五：只读观测保证。
 * 覆盖：冷却到期后只读查询不推进 OPEN->HALF_OPEN、不改变后续请求判定、
 * 不计失败/流量统计；同一时刻反复查询结果一致；状态时间线有界、写满覆盖最旧、
 * 并发追加与查询下不丢记录不错乱（序号单调、按时间正序）。
 */
class ReadOnlyObservationTest {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyObservationTest.class);

    private ScoreRequest req(String id, double seed) {
        return new ScoreRequest(id, "v1", List.of(seed, seed, seed));
    }

    @Test
    @DisplayName("只读查询不推进恢复、不占试探名额、不计流量与失败统计")
    void readOnlyQueriesNeverAdvanceRecoveryOrChangeOutcome() {
        ManualTimeSource clock = new ManualTimeSource();
        var policy = new RecoveryPolicy(2, 1_000L, 2.0d, 10_000L);
        var h = TestGatewayFactory.build(policy, clock, 60_000L, 0L);
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        h.service().score(req("t-1", 1));
        h.service().score(req("t-2", 2));
        assertEquals(CircuitState.OPEN, cb.viewState());
        long trafficBefore = h.router().trafficCounts().get("ml-score").get("v1");

        // 冷却早已到期：高频、并发地只读查询
        clock.advanceMs(5_000L);
        for (int i = 0; i < 100; i++) {
            assertEquals(CircuitState.OPEN, cb.viewState(), "只读 viewState 不得推进状态");
            assertEquals(CircuitState.OPEN,
                    h.circuits().snapshotStates().get("cpu-v1-1"),
                    "只读 snapshotStates 不得推进状态");
            BackendHealth snapshot = cb.health();
            assertEquals(CircuitState.OPEN, snapshot.state(), "只读 health 不得推进状态");
            assertFalse(snapshot.probeInFlight(), "查询不得占用试探名额");
            assertEquals(0L, snapshot.remainingCooldownMs(), "冷却到期后剩余应为 0");
            h.circuits().snapshotHealth();
            h.router().trafficCounts();
            h.router().weightSnapshot();
        }

        // 试探名额仍然空闲：下一个真实请求必须能正常试探（没有“再也恢复不了”）
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        ScoreResult recovered = h.service().score(req("recover", 3));
        assertTrue(recovered.success());
        assertEquals("cpu-v1-1", recovered.backendId());
        assertTrue(recovered.decisionReason().contains("RECOVERED_AFTER_PROBE"));
        assertEquals(CircuitState.CLOSED, cb.viewState());

        // 观测不计入真实流量统计
        long trafficAfter = h.router().trafficCounts().get("ml-score").get("v1");
        assertEquals(trafficBefore + 1, trafficAfter, "只读查询不得计入灰度流量（仅 +1 真实请求）");
        // 失败计数未被查询污染：恢复后为 0
        assertEquals(0, cb.health().failureCount());
        log.info("[只读不推进] 冷却到期后 300+ 次只读查询状态保持 OPEN；首个真实请求仍成功试探恢复");
    }

    @Test
    @DisplayName("并发高频只读快照内容完全一致")
    void concurrentReadOnlySnapshotsAreConsistentDuringRecovery() throws Exception {
        ManualTimeSource clock = new ManualTimeSource();
        var policy = new RecoveryPolicy(2, 1_000L, 2.0d, 10_000L);
        var h = TestGatewayFactory.build(policy, clock, 60_000L, 0L);
        CircuitBreaker cb = h.circuits().forBackend("cpu-v1-1");
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        h.service().score(req("t-1", 1));
        h.service().score(req("t-2", 2));

        int readers = 8;
        int perReader = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        ConcurrentLinkedQueue<BackendHealth> snapshots = new ConcurrentLinkedQueue<>();
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perReader; i++) {
                        snapshots.add(cb.health());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // 没有任何真实请求：所有快照必须一致——OPEN、计数 2、时间线只有 1 条隔离记录
        assertEquals(readers * perReader, snapshots.size());
        for (BackendHealth s : snapshots) {
            assertEquals(CircuitState.OPEN, s.state());
            assertEquals(2, s.failureCount());
            assertEquals(1, s.timeline().size(), "只读快照不得新增时间线记录");
            assertEquals(TimelineReason.FAILURE_THRESHOLD_REACHED,
                    s.timeline().get(0).reason());
        }
        log.info("[只读一致性] 并发 {} 个读线程 x{} 次快照全部一致：OPEN/failureCount=2/timeline=1",
                readers, perReader);
    }

    @Test
    @DisplayName("时间线有界覆盖最旧、序号单调、并发追加查询不丢不错")
    void timelineIsBounded_overwritesOldest_staysOrderedAndDedupedUnderConcurrency()
            throws Exception {
        int capacity = 5;
        StateTimeline timeline = new StateTimeline(capacity);

        // 单线程：写满后覆盖最旧，序号单调、按时间正序
        for (int i = 0; i < 8; i++) {
            timeline.append(100L + i, CircuitState.CLOSED, CircuitState.OPEN,
                    TimelineReason.PROBE_FAILED, "n=" + i);
        }
        assertEquals(capacity, timeline.size());
        List<TimelineEvent> snap = timeline.snapshot();
        assertEquals(4L, snap.get(0).seq(), "应保留最近 5 条 seq=4..8，最旧为 seq=4");
        assertEquals(8L, snap.get(4).seq(), "最新一条 seq=8");
        long prevSeq = 0L;
        long prevTs = -1L;
        for (TimelineEvent e : snap) {
            assertTrue(e.seq() > prevSeq, "快照必须按序号正序");
            assertTrue(e.timestampMs() >= prevTs, "快照必须按时间正序");
            prevSeq = e.seq();
            prevTs = e.timestampMs();
        }
        // 快照是独立副本：再追加不改变已取快照
        timeline.append(200L, CircuitState.HALF_OPEN, CircuitState.CLOSED,
                TimelineReason.PROBE_SUCCEEDED, "recovered");
        assertEquals(5, snap.size(), "已返回的快照不得被后续写入改写");

        // 并发追加 + 并发查询：不丢记录（容量内）、不重复、不错乱
        StateTimeline shared = new StateTimeline(50);
        int writers = 4;
        int writesEach = 100;
        int readers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(writers + readers);
        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesEach; i++) {
                        shared.append(System.currentTimeMillis(), CircuitState.OPEN,
                                CircuitState.HALF_OPEN, TimelineReason.COOLDOWN_EXPIRED_PROBE,
                                "x");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }
        ConcurrentLinkedQueue<List<TimelineEvent>> readerSnaps = new ConcurrentLinkedQueue<>();
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        List<TimelineEvent> s = shared.snapshot();
                        long last = 0L;
                        for (TimelineEvent e : s) {
                            assertTrue(e.seq() > last, "并发读到的快照也必须序号正序不重复");
                            last = e.seq();
                        }
                        readerSnaps.add(s);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(allDone.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        List<TimelineEvent> finalSnap = shared.snapshot();
        assertEquals(50, finalSnap.size());
        assertEquals(writers * writesEach, finalSnap.get(49).seq(),
                "最终一条序号必须等于总写入数：写入不得丢失");
        assertEquals(351L, finalSnap.get(0).seq(), "最旧一条应为 seq=351（总 400 条覆盖最近 50）");
        assertFalse(readerSnaps.isEmpty());
        log.info("[时间线有界] 容量=5 写8条后保留seq=5..8；并发写 {} 条全部落表(最终seq={})，读快照始终正序",
                writers * writesEach, finalSnap.get(49).seq());
    }
}
