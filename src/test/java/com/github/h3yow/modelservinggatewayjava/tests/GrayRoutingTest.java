package com.github.h3yow.modelservinggatewayjava.tests;

import com.github.h3yow.modelservinggatewayjava.model.ModelVersion;
import com.github.h3yow.modelservinggatewayjava.model.RejectReason;
import com.github.h3yow.modelservinggatewayjava.model.ScoreResponse;
import com.github.h3yow.modelservinggatewayjava.routing.RouteDecision;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.github.h3yow.modelservinggatewayjava.tests.TestKit.env;
import static com.github.h3yow.modelservinggatewayjava.tests.TestKit.req;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景一：灰度发布与版本路由。
 * 覆盖：精确版本、未注册/停用/零权重的可区分拒绝、加权流量分配可观测、
 *      权重切换后实际流量切换且旧缓存失效。
 */
class GrayRoutingTest {

    private static final Logger log = LoggerFactory.getLogger(GrayRoutingTest.class);

    @Test
    void pinnedVersion_hitsExactVersion_andLogsDecision() {
        var e = env(80, 20, 200, 3, 2000);
        ScoreResponse r = e.service.score(req("v2", "feature-1001"));

        log.info("[test] request={} -> success={} hitVersion={} backend={} decision='{}'",
                "fraud/v2/feature-1001", r.success(), r.modelVersion(), r.backendId(), r.decision());

        assertTrue(r.success());
        assertEquals("v2", r.modelVersion());
        assertEquals("backend-b", r.backendId());
        assertTrue(r.decision().contains("pinned version=v2"));
    }

    @Test
    void unregisteredVersion_isRejected_notSilentlyFallback() {
        var e = env(80, 20, 200, 3, 2000);
        ScoreResponse r = e.service.score(req("v9-not-exist", "feature-1002"));

        log.info("[test] request={} -> success={} reason={} decision='{}'",
                "fraud/v9/feature-1002", r.success(), r.reason(), r.decision());

        assertFalse(r.success());
        assertEquals(RejectReason.VERSION_NOT_REGISTERED, r.reason());
        assertNull(r.backendId(), "未注册版本不得静默回退到默认版本");
    }

    @Test
    void disabledVersion_isRejectedWithDistinctReason() {
        var e = env(80, 20, 200, 3, 2000);
        e.registry.setEnabled("fraud", "v2", false);
        ScoreResponse r = e.service.score(req("v2", "feature-1003"));

        log.info("[test] request={} -> reason={} decision='{}'",
                "fraud/v2(disabled)", r.reason(), r.decision());

        assertFalse(r.success());
        assertEquals(RejectReason.VERSION_DISABLED, r.reason());
    }

    @Test
    void zeroWeightVersion_isRejectedAsIncompatible() {
        var e = env(100, 0, 200, 3, 2000);
        ScoreResponse r = e.service.score(req("v2", "feature-1004"));

        log.info("[test] request={} -> reason={} decision='{}'",
                "fraud/v2(weight=0)", r.reason(), r.decision());

        assertFalse(r.success());
        assertEquals(RejectReason.VERSION_INCOMPATIBLE, r.reason());
    }

    @Test
    void weightedDistribution_matchesConfiguredWeights_andIsObservable() {
        var e = env(80, 20, 200, 3, 2000);
        int n = 4000;
        for (int i = 0; i < n; i++) {
            ScoreResponse r = e.service.score(req(null, "hash-" + i));
            assertTrue(r.success());
        }

        long v1 = e.router.routedCount("fraud", "v1");
        long v2 = e.router.routedCount("fraud", "v2");
        double v1Pct = v1 * 100.0 / n;
        log.info("[test] 灰度实际流量(权重80/20): {} v1占比={:.1}%", e.router.trafficSnapshot(), v1Pct);
        System.out.println("[test] traffic snapshot: " + e.router.trafficSnapshot()
                + " v1Pct=" + v1Pct + "%");

        assertEquals(n, v1 + v2);
        assertTrue(v1Pct >= 75.0 && v1Pct <= 85.0,
                "实际流量应接近配置权重 80/20，实际 v1=" + v1Pct + "%");
    }

    @Test
    void deterministicPinnedPoint_picksByCumulativeWeight() {
        var e = env(70, 30, 200, 3, 2000);
        List<ModelVersion> c = e.registry.candidates("fraud");

        RouteDecision atZero = e.router.route(c, 0L);
        RouteDecision atBoundary = e.router.route(c, 70L);
        log.info("[test] 确定性选点: point=0 -> {}; point=70 -> {}",
                atZero.version().version(), atBoundary.version().version());

        assertEquals("v1", atZero.version().version());
        assertEquals("v2", atBoundary.version().version());
        assertTrue(atZero.basis().contains("picked=v1"));
    }

    @Test
    void weightSwitch_reroutesTraffic_andInvalidatesOldCache() {
        var e = env(100, 0, 200, 3, 2000);

        ScoreResponse before = e.service.score(req(null, "feature-shared"));
        assertEquals("v1", before.modelVersion());
        assertFalse(before.cacheHit());

        // 第二次相同请求命中 v1 缓存
        ScoreResponse cached = e.service.score(req(null, "feature-shared"));
        assertTrue(cached.cacheHit());
        assertEquals("v1", cached.modelVersion());

        // 灰度切换：100% -> v2；权重调整必须使旧缓存失效
        e.registry.updateWeight("fraud", "v1", 0);
        e.registry.updateWeight("fraud", "v2", 100);
        e.service.invalidateCache();

        ScoreResponse after = e.service.score(req(null, "feature-shared"));
        log.info("[test] 灰度切换后相同请求: before={} after={} cacheHit={} decision='{}'",
                before.modelVersion(), after.modelVersion(), after.cacheHit(), after.decision());

        assertEquals("v2", after.modelVersion());
        assertEquals("backend-b", after.backendId());
        assertFalse(after.cacheHit(), "权重调整后旧缓存必须失效并重新计算");
    }
}
