package com.github.h3yow.modelservinggatewayjava.gateway;

import com.github.h3yow.modelservinggatewayjava.gateway.model.GatewayErrorCode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景一：灰度切换与按版本路由。
 * 覆盖：默认 100/0 流量、切到 0/100、显式钉住零权重版本、
 * 未注册版本拒绝（不静默回退）、不兼容版本拒绝、切换后缓存失效。
 */
class CanaryRoutingTest {

    private static final Logger log = LoggerFactory.getLogger(CanaryRoutingTest.class);

    private ScoreRequest req(String id, String version, double... feats) {
        List<Double> fs = new java.util.ArrayList<>();
        for (double f : feats) {
            fs.add(f);
        }
        return new ScoreRequest(id, version, fs);
    }

    @Test
    void defaultWeightsRouteAllFamilyTrafficToV1() {
        var h = TestGatewayFactory.build(3, 1000, 60000, 0);
        int v1 = 0;
        int v2 = 0;
        for (int i = 0; i < 200; i++) {
            ScoreResult r = h.service().score(req("r" + i, "ml-score", 1, 2, 3));
            assertTrue(r.success(), "family 请求应成功");
            log.info("[灰度默认] request={} 命中版本={} 判定依据={} 后端={} 分数={}",
                    r.requestId(), r.servedVersion(), r.decisionReason(),
                    r.backendId(), r.result());
            if ("v1".equals(r.servedVersion())) {
                v1++;
            } else {
                v2++;
            }
        }
        assertEquals(200, v1, "v1 权重 100 应承载全部流量");
        assertEquals(0, v2, "v2 权重 0 不应接收灰度流量");
        log.info("[灰度默认] 流量统计 v1={} v2={} 观测={}", v1, v2, h.router().trafficCounts());
        assertEquals(200L, h.router().trafficCounts().get("ml-score").get("v1"));
    }

    @Test
    void switchingWeightsMovesTrafficToV2_andInvalidatesCache() {
        var h = TestGatewayFactory.build(3, 1000, 60000, 0);

        // 先在 v1 产生一个缓存
        ScoreResult first = h.service().score(req("a-1", "ml-score", 1, 2, 3));
        assertEquals("v1", first.servedVersion());

        // 权重切到 0/100 -> 纪元递增 -> 旧缓存失效
        h.router().updateWeights("ml-score", java.util.Map.of("v1", 0, "v2", 100));
        log.info("[灰度切换] 权重调整后 epoch={} weights={}",
                h.epoch().current(), h.router().weightSnapshot());

        int v2 = 0;
        for (int i = 0; i < 100; i++) {
            ScoreResult r = h.service().score(req("s" + i, "ml-score", 1, 2, 3, 4));
            assertTrue(r.success());
            assertEquals("v2", r.servedVersion(), "切流后应全部命中 v2");
            log.info("[灰度切换] request={} 命中版本={} 判定依据={} 后端={}",
                    r.requestId(), r.servedVersion(), r.decisionReason(), r.backendId());
            v2++;
        }
        assertEquals(100, v2);
        assertEquals(1L, h.epoch().current(), "权重调整应推进纪元");
        assertTrue(h.cache().invalidationCount() >= 1, "权重调整后旧缓存必须失效");
        log.info("[灰度切换] 切换后流量统计={} 缓存失效次数={}",
                h.router().trafficCounts(), h.cache().invalidationCount());
    }

    @Test
    void explicitZeroWeightVersionIsStillServedAndNotFallback() {
        var h = TestGatewayFactory.build(3, 1000, 60000, 0);
        h.router().updateWeights("ml-score", java.util.Map.of("v1", 0, "v2", 100));

        ScoreResult r = h.service().score(req("pin-1", "v1", 1, 2, 3));
        assertTrue(r.success());
        assertEquals("v1", r.servedVersion(), "显式钉住 v1 即便权重为 0 也必须服务");
        assertTrue(r.decisionReason().contains("ZERO_WEIGHT_EXPLICIT"),
                "判定依据需标注零权重显式请求，实际=" + r.decisionReason());
        log.info("[零权重显式] request={} 命中版本={} 判定依据={}",
                r.requestId(), r.servedVersion(), r.decisionReason());
    }

    @Test
    void unregisteredVersionIsRejectedWithoutSilentFallback() {
        var h = TestGatewayFactory.build(3, 1000, 60000, 0);
        ScoreResult r = h.service().score(req("u-1", "ml-score-v999", 1, 2, 3));
        assertFalse(r.success());
        assertEquals(GatewayErrorCode.VERSION_NOT_REGISTERED.name(), r.errorCode());
        assertEquals(null, r.servedVersion(), "不得回退到任何默认版本");
        log.info("[未注册拒绝] request={} 请求版本=ml-score-v999 判定依据={} 错误码={} 原因={}",
                r.requestId(), r.decisionReason(), r.errorCode(), r.errorMessage());
    }

    @Test
    void incompatibleFeatureDimensionIsRejectedDistinctly() {
        var h = TestGatewayFactory.build(3, 1000, 60000, 0);
        // v1 需要 3 维，给 4 维
        ScoreResult r = h.service().score(req("d-1", "v1", 1, 2, 3, 4));
        assertFalse(r.success());
        assertEquals(GatewayErrorCode.VERSION_INCOMPATIBLE.name(), r.errorCode(),
                "维度不匹配需与未注册区分");
        log.info("[不兼容拒绝] request={} 请求版本=v1(期望3维) 实际4维 判定依据={} 错误码={} 原因={}",
                r.requestId(), r.decisionReason(), r.errorCode(), r.errorMessage());
    }

    @Test
    void blankVersionIsRejectedAsInvalidRequest() {
        var h = TestGatewayFactory.build(3, 1000, 60000, 0);
        ScoreResult r = h.service().score(req("b-1", "  ", 1));
        assertFalse(r.success());
        assertEquals(GatewayErrorCode.INVALID_REQUEST.name(), r.errorCode());
        log.info("[非法请求] request={} 空版本 判定依据={} 错误码={}",
                r.requestId(), r.decisionReason(), r.errorCode());
    }
}
