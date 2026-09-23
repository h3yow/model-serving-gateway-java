package com.github.h3yow.modelservinggatewayjava.tests;

import com.github.h3yow.modelservinggatewayjava.backend.InMemoryBackend;
import com.github.h3yow.modelservinggatewayjava.model.RejectReason;
import com.github.h3yow.modelservinggatewayjava.model.ScoreResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.github.h3yow.modelservinggatewayjava.tests.TestKit.env;
import static com.github.h3yow.modelservinggatewayjava.tests.TestKit.req;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景二：后端异常分级处理与熔断恢复。
 * 覆盖：超时/非法/内部错误映射为不同稳定结果、熔断阈值与 CIRCUIT_OPEN、
 *      熔断期间已完成缓存结果不被改写（降级可读）、冷却后试探恢复。
 */
class BackendFailureTest {

    private static final Logger log = LoggerFactory.getLogger(BackendFailureTest.class);

    @Test
    void injectedFailures_mapToDistinctStableReasons() {
        var e = env(0, 100, 200, 10, 60_000);

        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_ILLEGAL);
        ScoreResponse illegal = e.service.score(req("v2", "f-illegal"));

        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_TIMEOUT);
        ScoreResponse timeout = e.service.score(req("v2", "f-timeout"));

        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_INTERNAL);
        ScoreResponse internal = e.service.score(req("v2", "f-internal"));

        log.info("[test] illegal -> reason={}; timeout -> reason={}; internal -> reason={}",
                illegal.reason(), timeout.reason(), internal.reason());

        assertEquals(RejectReason.BACKEND_ILLEGAL_ARGUMENT, illegal.reason());
        assertEquals(RejectReason.BACKEND_TIMEOUT, timeout.reason());
        assertEquals(RejectReason.BACKEND_INTERNAL_ERROR, internal.reason());
        assertNotEquals(illegal.reason(), timeout.reason());
        assertNotEquals(timeout.reason(), internal.reason());
    }

    @Test
    void slowBackend_mapsToTimeout_andCircuitOpensAfterThreshold() {
        // 后端延迟 500ms > 网关超时 100ms；熔断阈值 3
        var e = env(0, 100, 100, 3, 60_000);
        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_NONE);
        // 直接给 backend-b 注入延迟：重建一个慢后端替换
        var slow = new TestKit.CountingBackend("backend-b", 500, InMemoryBackend.FAIL_NONE);
        e.backends.put("backend-b", slow);

        for (int i = 1; i <= 3; i++) {
            ScoreResponse r = e.service.score(req("v2", "slow-" + i));
            log.info("[test] 第{}次慢调用 -> reason={} breaker={}", i, r.reason(),
                    e.breakers.get("backend-b").state());
            assertEquals(RejectReason.BACKEND_TIMEOUT, r.reason());
        }

        // 第 4 次：熔断已打开，不再调用后端
        int callsBefore = slow.calls();
        ScoreResponse r4 = e.service.score(req("v2", "slow-4"));
        log.info("[test] 熔断后请求 -> reason={} breaker={} backendCalls(不变)={}",
                r4.reason(), e.breakers.get("backend-b").state(), slow.calls());

        assertEquals(RejectReason.CIRCUIT_OPEN, r4.reason());
        assertEquals(callsBefore, slow.calls(), "熔断期间不得再调用后端");
    }

    @Test
    void circuitOpen_doesNotRewriteCompletedResults_andRecoversAfterCooldown()
            throws InterruptedException {
        // 阈值 2，冷却 300ms
        var e = env(0, 100, 200, 2, 300);

        // 1) 先成功一次，结果进入缓存
        ScoreResponse ok = e.service.score(req("v2", "stable-feature"));
        assertTrue(ok.success());
        double okScore = ok.score();

        // 2) 注入内部错误，连续 2 次触发熔断
        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_INTERNAL);
        ScoreResponse f1 = e.service.score(req("v2", "boom-1"));
        ScoreResponse f2 = e.service.score(req("v2", "boom-2"));
        assertEquals(RejectReason.BACKEND_INTERNAL_ERROR, f1.reason());
        assertEquals(RejectReason.BACKEND_INTERNAL_ERROR, f2.reason());

        // 3) 熔断期间：新特征 -> CIRCUIT_OPEN；已完成的缓存结果仍可返回且不被改写
        ScoreResponse duringNew = e.service.score(req("v2", "boom-3"));
        ScoreResponse duringCached = e.service.score(req("v2", "stable-feature"));
        log.info("[test] 熔断期间: 新特征 reason={}; 缓存特征 success={} score={} (原score={})",
                duringNew.reason(), duringCached.success(), duringCached.score(), okScore);

        assertEquals(RejectReason.CIRCUIT_OPEN, duringNew.reason());
        assertTrue(duringCached.success(), "熔断期间已完成的缓存结果应可降级返回");
        assertEquals(okScore, duringCached.score(), "已完成结果不得被改写");
        assertTrue(duringCached.cacheHit());

        // 4) 冷却结束 + 后端恢复 -> 试探成功后重新承载流量
        Thread.sleep(350);
        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_NONE);
        ScoreResponse recovered = e.service.score(req("v2", "after-recovery"));
        log.info("[test] 恢复后请求 -> success={} version={} breaker={}",
                recovered.success(), recovered.modelVersion(),
                e.breakers.get("backend-b").state());

        assertTrue(recovered.success());
        assertEquals("v2", recovered.modelVersion());
    }

    @Test
    void illegalArgument_doesNotCountTowardBackendHealth() {
        // 非法请求是调用方问题，不应把后端熔断
        var e = env(0, 100, 200, 2, 60_000);
        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_ILLEGAL);

        for (int i = 0; i < 5; i++) {
            ScoreResponse r = e.service.score(req("v2", "bad-" + i));
            assertEquals(RejectReason.BACKEND_ILLEGAL_ARGUMENT, r.reason());
        }
        e.backendB.setInjectedFailure(InMemoryBackend.FAIL_NONE);
        ScoreResponse ok = e.service.score(req("v2", "good-1"));
        log.info("[test] 连续非法请求后正常请求 -> success={} breaker={}",
                ok.success(), e.breakers.get("backend-b").state());
        assertTrue(ok.success(), "非法请求不应计入后端健康度导致熔断");
    }
}
