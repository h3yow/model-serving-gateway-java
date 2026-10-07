package com.github.h3yow.modelservinggatewayjava.gateway.web;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendType;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.FailureMode;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.InMemoryInferenceBackend;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.InferenceBackend;
import com.github.h3yow.modelservinggatewayjava.gateway.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreakerRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.CanaryRouter;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ConfigEpoch;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ModelVersionSpec;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.RoutingException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理接口：灰度权重调整、版本升级注册、流量观测、熔断状态观测、
 * 后端失败注入与缓存清理。
 */
@RestController
public class AdminController {

    private final CanaryRouter router;
    private final BackendRegistry backends;
    private final CircuitBreakerRegistry circuits;
    private final ScoringCache cache;
    private final ConfigEpoch epoch;

    public AdminController(CanaryRouter router, BackendRegistry backends,
                           CircuitBreakerRegistry circuits, ScoringCache cache,
                           ConfigEpoch epoch) {
        this.router = router;
        this.backends = backends;
        this.circuits = circuits;
        this.cache = cache;
        this.epoch = epoch;
    }

    /** 调整某版本线灰度权重；权重调整立即生效并推进纪元使缓存失效。 */
    @PostMapping("/admin/families/{family}/weights")
    public Map<String, Object> updateWeights(@PathVariable String family,
                                             @RequestBody Map<String, Integer> weights) {
        try {
            router.updateWeights(family, weights);
        } catch (RoutingException e) {
            return Map.of("ok", false, "errorCode", e.getErrorCode().name(),
                    "message", e.getMessage());
        }
        return Map.of("ok", true, "family", family, "epoch", epoch.current(),
                "weights", router.weightSnapshot().get(family));
    }

    /**
     * 注册/升级一个新版本并挂一个内存后端；版本升级推进纪元使旧缓存失效。
     * 请求体形如 {"version":"ml-score-v3","family":"ml-score",
     * "expectedFeatureDim":3,"backendType":"GPU","backendId":"gpu-v3-1"}。
     */
    @PostMapping("/admin/versions")
    public Map<String, Object> registerVersion(@RequestBody Map<String, Object> body) {
        String version = (String) body.get("version");
        String family = (String) body.get("family");
        int dim = Integer.parseInt(String.valueOf(body.get("expectedFeatureDim")));
        String type = String.valueOf(body.getOrDefault("backendType", "CPU"));
        String backendId = (String) body.getOrDefault("backendId", version + "-admin-1");

        ModelVersionSpec spec = new ModelVersionSpec(version, family, dim);
        router.registerVersion(spec);
        if (backends.findById(backendId) == null) {
            backends.register(new InMemoryInferenceBackend(backendId,
                    BackendType.valueOf(type), version));
        }
        long e = epoch.bump(); // 版本升级 -> 缓存失效
        return Map.of("ok", true, "registeredVersion", version, "backendId", backendId,
                "epoch", e);
    }

    /** 观测实际灰度流量分配、生效权重与当前纪元。 */
    @GetMapping("/admin/traffic")
    public Map<String, Object> traffic() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("epoch", epoch.current());
        out.put("weights", router.weightSnapshot());
        out.put("traffic", router.trafficCounts());
        return out;
    }

    /**
     * 观测各后端熔断状态与自愈进度（纯只读：不推进恢复、不计入任何统计，
     * 并发/高频/连续查询结果一致）。
     */
    @GetMapping("/admin/circuits")
    public Map<String, Object> circuits() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("states", circuits.snapshotStates());
        out.put("health", circuits.healthSnapshot());
        return out;
    }

    /** 单个后端的只读健康快照：阶段、剩余冷却、退避轮次、状态时间线。 */
    @GetMapping("/admin/backends/{backendId}/health")
    public Map<String, Object> backendHealth(@PathVariable String backendId) {
        var health = circuits.healthOf(backendId);
        if (health == null) {
            return Map.of("ok", false, "message", "unknown backend: " + backendId);
        }
        return Map.of("ok", true, "health", health);
    }

    @GetMapping("/admin/cache")
    public Map<String, Object> cacheInfo() {
        return Map.of("size", cache.size(), "epoch", cache.cachedAtEpoch(),
                "invalidationCount", cache.invalidationCount());
    }

    @DeleteMapping("/admin/cache")
    public Map<String, Object> clearCache() {
        cache.invalidate();
        return Map.of("ok", true, "size", cache.size());
    }

    /** 给指定后端注入失败模式：NONE / TIMEOUT / INTERNAL_ERROR / INVALID_REQUEST。 */
    @PostMapping("/admin/backends/{backendId}/failure")
    public Map<String, Object> injectFailure(@PathVariable String backendId,
                                             @RequestParam String mode) {
        InferenceBackend b = backends.findById(backendId);
        if (!(b instanceof InMemoryInferenceBackend mem)) {
            return Map.of("ok", false, "message", "backend not found: " + backendId);
        }
        FailureMode fm = FailureMode.valueOf(mode);
        mem.setFailureMode(fm);
        return Map.of("ok", true, "backendId", backendId, "failureMode", fm.name());
    }

    /** 测试支持：复位熔断、清理缓存并解除全部失败注入。 */
    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        circuits.resetAll();
        cache.invalidate();
        for (String v : backends.registeredVersions()) {
            for (InferenceBackend b : backends.backendsFor(v)) {
                if (b instanceof InMemoryInferenceBackend mem) {
                    mem.setFailureMode(FailureMode.NONE);
                }
            }
        }
        return Map.of("ok", true);
    }
}
