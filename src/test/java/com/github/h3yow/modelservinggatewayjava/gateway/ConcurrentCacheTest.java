package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.model.BatchScoreResponse;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景三：带缓存的批量评分与并发重复请求。
 * 覆盖：TTL 内相同请求不重复计算、并发下只计算一次、结果不串号、
 * 权重调整/版本升级使缓存失效、批量结果顺序稳定。
 */
class ConcurrentCacheTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentCacheTest.class);

    private ScoreRequest req(String id, String version, double... feats) {
        List<Double> fs = new java.util.ArrayList<>();
        for (double f : feats) {
            fs.add(f);
        }
        return new ScoreRequest(id, version, fs);
    }

    @Test
    void sameRequestWithinTtlIsComputedOnlyOnce() {
        // 后端有 80ms 延迟，便于观察缓存收益
        var h = TestGatewayFactory.build(3, 10_000, 60_000, 80);
        ScoreResult first = h.service().score(req("c-1", "v1", 1, 2, 3));
        ScoreResult second = h.service().score(req("c-2", "v1", 1, 2, 3));

        assertTrue(first.success());
        assertFalse(first.fromCache(), "首次为实际计算");
        assertTrue(second.fromCache(), "TTL 内第二次必须命中缓存");
        assertEquals("CACHE_HIT", second.decisionReason());
        assertEquals(1, TestGatewayFactory.counting(h.backends(), first.backendId()).invocations.get(),
                "相同请求不得重复计算");
        assertEquals(first.result(), second.result(), "结果必须一致");
        assertEquals("c-2", second.requestId(), "缓存结果必须绑定当前请求 id，不得串号");
        log.info("[缓存命中] first(request={},计算,后端={}) second(request={},命中={}) 分数均={}",
                first.requestId(), first.backendId(), second.requestId(),
                second.decisionReason(), first.result());
    }

    @Test
    void concurrentIdenticalRequestsAreComputedExactlyOnceAndNotMixedUp() throws Exception {
        int threads = 32;
        var h = TestGatewayFactory.build(3, 10_000, 60_000, 120);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<ScoreResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String id = "cc-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    ScoreResult r = h.service().score(req(id, "v1", 7, 8, 9));
                    results.add(r);
                    if (!r.success()) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发请求应在超时前完成");
        pool.shutdown();

        assertEquals(0, failures.get(), "不应有失败");
        assertEquals(threads, results.size());

        int computed = 0;
        int dedupHits = 0;
        int ttlHits = 0;
        Double expectedValue = null;
        for (ScoreResult r : results) {
            assertEquals("v1", r.servedVersion());
            if (!r.fromCache()) {
                computed++;
                expectedValue = r.result();
            } else if ("CONCURRENT_DEDUP".equals(r.decisionReason())) {
                dedupHits++;
            } else if ("CACHE_HIT".equals(r.decisionReason())) {
                ttlHits++;
            }
        }
        // 同版本两个后端：但相同 (version,features) 键只有一个 owner 真正计算
        int totalBackendCalls = TestGatewayFactory.counting(h.backends(), "cpu-v1-1").invocations.get()
                + TestGatewayFactory.counting(h.backends(), "cpu-v1-2").invocations.get();
        assertEquals(1, totalBackendCalls, "并发相同请求在整个版本上只能实际计算一次");
        assertEquals(1, computed, "仅一个请求为计算者");
        assertEquals(threads - 1, dedupHits + ttlHits, "其余请求均为去重/缓存命中，不重复计算");

        // 结果不串号：每个结果的 requestId/value 与各自请求对应，值完全一致
        for (ScoreResult r : results) {
            assertEquals(expectedValue, r.result(), "所有并发结果值必须一致");
            assertTrue(r.requestId().startsWith("cc-"), "结果必须绑定各自 requestId");
        }
        long distinctIds = results.stream().map(ScoreResult::requestId).distinct().count();
        assertEquals(threads, distinctIds, "requestId 不得串号");
        log.info("[并发去重] 线程数={} 实际计算={} 并发合并命中={} 缓存命中={} 后端总调用={} 统一分数={}",
                threads, computed, dedupHits, ttlHits, totalBackendCalls, expectedValue);
    }

    @Test
    void weightChangeInvalidatesCacheAndRecomputes() {
        var h = TestGatewayFactory.build(3, 10_000, 60_000, 0);
        // v1 上先算并缓存
        ScoreResult v1 = h.service().score(req("w-1", "v1", 1, 2, 3));
        assertEquals(1, h.cache().size());

        // 显式调整权重 -> 纪元推进 -> 缓存整体失效
        h.router().updateWeights("ml-score", Map.of("v1", 50, "v2", 50));
        assertEquals(0, h.cache().size(), "权重调整后缓存必须失效");

        ScoreResult v1Again = h.service().score(req("w-2", "v1", 1, 2, 3));
        assertFalse(v1Again.fromCache(), "失效后必须重新计算");
        assertEquals(v1.result(), v1Again.result(), "同版本确定性评分仍一致");
        log.info("[权重失效缓存] 调整后 epoch={} cacheSize={} 重新计算后端={} 分数={}",
                h.epoch().current(), h.cache().size(), v1Again.backendId(), v1Again.result());
    }

    @Test
    void batchPreservesOrderAndDeduplicates() {
        var h = TestGatewayFactory.build(3, 10_000, 60_000, 0);
        List<ScoreRequest> requests = List.of(
                req("b-1", "v1", 1, 2, 3),
                req("b-2", "v1", 4, 5, 6),
                req("b-1-dup", "v1", 1, 2, 3),   // 与 b-1 相同 -> 命中
                req("b-3", "v1", 7, 8, 9));
        BatchScoreResponse resp = h.service().batch(requests);

        assertEquals(4, resp.results().size());
        assertEquals(List.of("b-1", "b-2", "b-1-dup", "b-3"),
                resp.results().stream().map(ScoreResult::requestId).toList(),
                "批量结果必须与请求顺序一一对应");
        assertTrue(resp.results().get(2).fromCache(), "批内重复请求应命中缓存");
        assertEquals(1, resp.cacheHits(), "本批次应有 1 次缓存命中");
        assertEquals(3, resp.cacheComputed(), "本批次应实际计算 3 个不同请求");

        // 结果不串号：重复请求 id 保持为自己的
        assertEquals("b-1-dup", resp.results().get(2).requestId());
        assertEquals(resp.results().get(0).result(), resp.results().get(2).result());
        log.info("[批量] 顺序={} cacheHits={} cacheComputed={} epoch={}",
                resp.results().stream().map(ScoreResult::requestId).toList(),
                resp.cacheHits(), resp.cacheComputed(), resp.cacheEpoch());
    }

    @Test
    void versionUpgradeBumpsEpochAndOldCacheIsNotServed() {
        var h = TestGatewayFactory.build(3, 10_000, 60_000, 0);
        h.service().score(req("u-1", "v1", 1, 2, 3));
        assertEquals(1, h.cache().size());
        long epochBefore = h.epoch().current();

        // 模拟版本升级：注册新版本并推进纪元
        h.router().registerVersion(new com.github.h3yow.modelservinggatewayjava.gateway
                .routing.ModelVersionSpec("v3", "ml-score", 3));
        h.backends().register(new TestGatewayFactory.CountingBackend(
                "cpu-v3-1", com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendType.CPU,
                "v3", 0));
        h.router().seedWeights("ml-score",
                Map.of("v1", 0, "v2", 0, "v3", 100));
        long newEpoch = h.epoch().bump();
        assertTrue(newEpoch > epochBefore);

        ScoreResult fresh = h.service().score(req("u-2", "v1", 1, 2, 3));
        assertFalse(fresh.fromCache(), "版本升级推进纪元后旧缓存不得再被服务");
        log.info("[版本升级] epoch {}->{} 旧缓存失效，v1 请求重新计算，后端={}",
                epochBefore, newEpoch, fresh.backendId());
    }
}
