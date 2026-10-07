package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.FailureMode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.GatewayErrorCode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景二：后端异常分级处理、降级与熔断。
 * 覆盖：超时/内部错误/非法请求映射为不同稳定错误码；
 * 单后端故障降级到同版本健康后端；熔断打开后快速失败、
 * 冷却后半开恢复；降级/熔断期间已完成结果不被改写。
 */
class BackendResilienceTest {

    private static final Logger log = LoggerFactory.getLogger(BackendResilienceTest.class);

    private ScoreRequest req(String id) {
        return new ScoreRequest(id, "v1", List.of(1.0, 2.0, 3.0));
    }

    @Test
    void timeoutAndInternalErrorMapToDistinctStableCodes() {
        // 两个后端都超时：遍历降级均失败，最终返回 BACKEND_TIMEOUT
        var hTimeout = TestGatewayFactory.build(3, 10_000, 60000, 0);
        TestGatewayFactory.counting(hTimeout.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.TIMEOUT);
        TestGatewayFactory.counting(hTimeout.backends(), "cpu-v1-2")
                .setFailureMode(FailureMode.TIMEOUT);
        ScoreResult t = hTimeout.service().score(req("t-code"));
        assertFalse(t.success());
        assertEquals(GatewayErrorCode.BACKEND_TIMEOUT.name(), t.errorCode());
        log.info("[异常分级-超时] request={} 判定依据={} 错误码={} 原因={}",
                t.requestId(), t.decisionReason(), t.errorCode(), t.errorMessage());

        var hInternal = TestGatewayFactory.build(3, 10_000, 60000, 0);
        TestGatewayFactory.counting(hInternal.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        TestGatewayFactory.counting(hInternal.backends(), "cpu-v1-2")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        ScoreResult ie = hInternal.service().score(req("ie-code"));
        assertFalse(ie.success());
        assertEquals(GatewayErrorCode.BACKEND_INTERNAL_ERROR.name(), ie.errorCode());
        log.info("[异常分级-内部错误] request={} 判定依据={} 错误码={} 原因={}",
                ie.requestId(), ie.decisionReason(), ie.errorCode(), ie.errorMessage());

        var hInvalid = TestGatewayFactory.build(3, 10_000, 60000, 0);
        TestGatewayFactory.counting(hInvalid.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INVALID_REQUEST);
        TestGatewayFactory.counting(hInvalid.backends(), "cpu-v1-2")
                .setFailureMode(FailureMode.INVALID_REQUEST);
        ScoreResult inv = hInvalid.service().score(req("inv-code"));
        assertFalse(inv.success());
        assertEquals(GatewayErrorCode.INVALID_REQUEST.name(), inv.errorCode());
        log.info("[异常分级-非法请求] request={} 判定依据={} 错误码={} 原因={}",
                inv.requestId(), inv.decisionReason(), inv.errorCode(), inv.errorMessage());

        // 三类错误码两两不同且稳定
        assertNotEquals(t.errorCode(), ie.errorCode());
        assertNotEquals(ie.errorCode(), inv.errorCode());
    }

    @Test
    void singleBackendFailureFallsBackToHealthyBackendOfSameVersion() {
        var h = TestGatewayFactory.build(3, 10_000, 60000, 0);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);

        ScoreResult r = h.service().score(req("fb-1"));
        assertTrue(r.success(), "应自动降级到健康后端");
        assertEquals("v1", r.servedVersion());
        assertEquals("cpu-v1-2", r.backendId(), "必须由健康的 cpu-v1-2 承载");
        assertTrue(r.decisionReason().contains("FALLBACK_HEALTHY_BACKEND"),
                "判定依据需标注降级，实际=" + r.decisionReason());
        assertTrue(r.decisionReason().contains("cpu-v1-1:BACKEND_INTERNAL_ERROR"),
                "判定依据需包含首个后端的失败尝试，实际=" + r.decisionReason());
        log.info("[降级] request={} 命中版本={} 后端={} 判定依据={} 分数={}",
                r.requestId(), r.servedVersion(), r.backendId(),
                r.decisionReason(), r.result());
    }

    @Test
    void invalidRequestDoesNotTripCircuitBreaker() {
        var h = TestGatewayFactory.build(2, 10_000, 60000, 0);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INVALID_REQUEST);
        for (int i = 0; i < 5; i++) {
            h.service().score(req("ni-" + i));
        }
        assertEquals(CircuitState.CLOSED,
                h.circuits().forBackend("cpu-v1-1").viewState(),
                "非法请求不应计入熔断");
        // 解除注入后该后端立即可用
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        ScoreResult ok = h.service().score(req("ni-ok"));
        assertEquals("cpu-v1-1", ok.backendId());
        log.info("[非法请求不熔断] 连续非法请求后熔断状态=CLOSED，恢复后后端={}", ok.backendId());
    }

    @Test
    void circuitOpensThenRecoversAfterCoolDown() throws Exception {
        int threshold = 2;
        long coolDown = 300;
        var h = TestGatewayFactory.build(threshold, coolDown, 60000, 0);
        // 两个后端都内部错误，便于触发熔断
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-2")
                .setFailureMode(FailureMode.INTERNAL_ERROR);

        // 制造失败直至熔断打开
        ScoreResult last = null;
        for (int i = 0; i < 4; i++) {
            last = h.service().score(req("trip-" + i));
        }
        assertFalse(last.success());
        assertEquals(CircuitState.OPEN, h.circuits().forBackend("cpu-v1-1").viewState());
        log.info("[熔断打开] 连续失败后状态={} 判定依据={} 错误码={}",
                h.circuits().snapshotStates(), last.decisionReason(), last.errorCode());

        // 熔断打开期间：所有后端都熔断 -> ALL_BACKENDS_UNAVAILABLE
        ScoreResult duringOpen = h.service().score(req("open-1"));
        assertFalse(duringOpen.success());
        assertEquals(GatewayErrorCode.ALL_BACKENDS_UNAVAILABLE.name(),
                duringOpen.errorCode());
        log.info("[熔断期间] request={} 判定依据={} 错误码={}",
                duringOpen.requestId(), duringOpen.decisionReason(), duringOpen.errorCode());

        // 冷却后半开试探：恢复后端 -> 试探成功 -> 关闭并重新承载流量
        Thread.sleep(coolDown + 100);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1").setFailureMode(FailureMode.NONE);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-2").setFailureMode(FailureMode.NONE);
        ScoreResult recovered = h.service().score(req("recover-1"));
        assertTrue(recovered.success(), "冷却恢复后应重新承载流量");
        assertEquals(CircuitState.CLOSED, h.circuits().forBackend("cpu-v1-1").viewState());
        log.info("[熔断恢复] request={} 后端={} 状态=CLOSED 判定依据={}",
                recovered.requestId(), recovered.backendId(), recovered.decisionReason());
    }

    @Test
    void completedResultIsNotRewrittenByLaterCircuitEvent() {
        var h = TestGatewayFactory.build(1, 60_000, 60000, 0);
        // 第一次成功，拿到确定结果
        ScoreResult good = h.service().score(req("stable-1"));
        assertTrue(good.success());
        double value = good.result();
        String backend = good.backendId();

        // 之后让两个后端都持续故障，触发熔断。
        // 注意：使用不同特征向量，避免与 stable-1 命中同一缓存而跳过真实调用。
        TestGatewayFactory.counting(h.backends(), "cpu-v1-1")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        TestGatewayFactory.counting(h.backends(), "cpu-v1-2")
                .setFailureMode(FailureMode.INTERNAL_ERROR);
        for (int i = 0; i < 3; i++) {
            h.service().score(new ScoreRequest("later-" + i, "v1",
                    List.of(1.0, 2.0, 3.0 + i)));
        }
        assertEquals(CircuitState.OPEN, h.circuits().forBackend(backend).viewState());

        // 已完成结果对象保持不变
        assertEquals(value, good.result());
        assertEquals(backend, good.backendId());
        assertTrue(good.success());
        log.info("[结果不可变] 早期成功结果 value={} backend={} 在后续熔断(OPEN)后保持不变",
                good.result(), good.backendId());
    }
}
