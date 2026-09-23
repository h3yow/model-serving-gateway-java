package com.github.h3yow.modelservinggatewayjava.tests;

import com.github.h3yow.modelservinggatewayjava.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.model.ScoreResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.h3yow.modelservinggatewayjava.tests.TestKit.env;
import static com.github.h3yow.modelservinggatewayjava.tests.TestKit.req;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景三：带缓存的批量/并发评分。
 * 覆盖：并发相同请求只计算一次且结果一致、批量去重、不同请求结果不串号、
 *      有效期内不重复计算、配置版本变化后缓存失效。
 */
class ConcurrencyCacheTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyCacheTest.class);

    @Test
    void concurrentIdenticalRequests_computeOnce_andReturnConsistentResult() throws Exception {
        var e = env(100, 0, 200, 5, 60_000);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ScoreResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return e.service.score(req("v1", "same-feature-9001"));
            }));
        }
        ready.await();
        long t0 = System.currentTimeMillis();
        start.countDown();

        Set<Double> scores = new HashSet<>();
        Set<String> versions = new HashSet<>();
        int cacheHits = 0;
        for (Future<ScoreResponse> f : futures) {
            ScoreResponse r = f.get();
            assertTrue(r.success());
            scores.add(r.score());
            versions.add(r.modelVersion());
            if (r.cacheHit()) {
                cacheHits++;
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[test] 并发相同请求 threads={} -> backendCalls={} 不同分数数={} 命中数={} 耗时={}ms",
                threads, e.backendA.calls(), scores.size(), cacheHits, elapsed);

        assertEquals(1, e.backendA.calls(), "并发相同请求只允许真正计算一次");
        assertEquals(1, scores.size(), "所有并发结果必须一致");
        assertEquals(Set.of("v1"), versions, "不得出现结果串号");
        assertEquals(threads - 1, cacheHits, "其余线程应共享缓存结果");
        pool.shutdown();
    }

    @Test
    void batchWithDuplicates_deduplicates_andDoesNotCrossTalk() {
        var e = env(50, 50, 200, 5, 60_000);

        // 10 个请求：只有 3 个不同 featureHash，且有重复
        List<ScoreRequest> requests = List.of(
                req("v1", "hash-X"), req("v2", "hash-Y"), req("v1", "hash-X"),
                req("v1", "hash-Z"), req("v2", "hash-Y"), req("v1", "hash-X"),
                req("v2", "hash-Y"), req("v1", "hash-Z"), req("v1", "hash-X"),
                req("v2", "hash-Z"));

        List<ScoreResponse> responses = e.service.scoreBatch(requests, 8);
        assertEquals(requests.size(), responses.size());

        long aCalls = e.backendA.calls();
        long bCalls = e.backendB.calls();
        log.info("[test] 批量10条(含重复) -> backend-a计算={}次 backend-b计算={}次", aCalls, bCalls);

        // v1: hash-X, hash-Z -> 2 次；v2: hash-Y, hash-Z -> 2 次
        assertEquals(2, aCalls, "v1 只有两个不同请求，不得重复计算");
        assertEquals(2, bCalls, "v2 只有两个不同请求，不得重复计算");

        // 结果按位置对应且不串号
        for (int i = 0; i < responses.size(); i++) {
            ScoreRequest q = requests.get(i);
            ScoreResponse r = responses.get(i);
            assertTrue(r.success());
            assertEquals(q.modelVersion(), r.modelVersion());
            assertEquals(q.featureHash().equals("hash-X") ? "v1" : q.modelVersion(),
                    r.modelVersion());
        }

        // 不同 featureHash 分数不同（确定性哈希）
        ScoreResponse x = e.service.score(req("v1", "hash-X"));
        ScoreResponse z = e.service.score(req("v1", "hash-Z"));
        assertNotEquals(x.score(), z.score(), "不同特征结果不得串号");
    }

    @Test
    void withinTtl_repeatedRequest_isServedFromCache() {
        var e = env(100, 0, 200, 5, 60_000);
        ScoreResponse first = e.service.score(req("v1", "ttl-feature"));
        ScoreResponse second = e.service.score(req("v1", "ttl-feature"));
        ScoreResponse third = e.service.score(req("v1", "ttl-feature"));

        log.info("[test] 有效期内重复请求: first.cacheHit={} second={} third={} backendCalls={}",
                first.cacheHit(), second.cacheHit(), third.cacheHit(), e.backendA.calls());

        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        assertTrue(third.cacheHit());
        assertEquals(1, e.backendA.calls());
        assertEquals(first.score(), second.score());
        assertEquals(first.score(), third.score());
    }

    @Test
    void cacheInvalidation_afterVersionUpgrade_forcesRecompute() {
        var e = env(100, 0, 200, 5, 60_000);
        ScoreResponse r1 = e.service.score(req("v1", "upgrade-feature"));
        assertFalse(r1.cacheHit());

        // 模型版本升级：启用 v2 并将权重全部切到 v2（configVersion 递增）
        e.registry.updateWeight("fraud", "v1", 0);
        e.registry.updateWeight("fraud", "v2", 100);
        e.service.invalidateCache();

        ScoreResponse r2 = e.service.score(req(null, "upgrade-feature"));
        log.info("[test] 版本升级后: r1={}(backend={}) r2={}(backend={}) r2.cacheHit={}",
                r1.modelVersion(), r1.backendId(), r2.modelVersion(), r2.backendId(),
                r2.cacheHit());

        assertEquals("v2", r2.modelVersion());
        assertFalse(r2.cacheHit(), "版本升级后缓存必须失效");
        assertNotEquals(r1.backendId(), r2.backendId());
    }
}
