package com.github.h3yow.modelservinggatewayjava.gateway.routing;

import com.github.h3yow.modelservinggatewayjava.gateway.model.GatewayErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 灰度路由器。
 *
 * <ul>
 *   <li>显式请求已注册的具体版本：直接路由到该版本（即使其灰度权重为 0 也不回退默认版本）；</li>
 *   <li>显式请求版本线（family）：按该 family 的灰度权重加权随机分配到具体版本；</li>
 *   <li>未注册的版本或版本线：抛 {@link RoutingException}，绝不静默回退。</li>
 * </ul>
 *
 * 实际流量按 (family -> version -> 计数) 记录，可通过管理接口观测灰度分配。
 */
public class CanaryRouter {

    private final Map<String, ModelVersionSpec> versions = new ConcurrentHashMap<>();
    private final Map<String, String> familyByVersion = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> weights = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> traffic = new ConcurrentHashMap<>();
    private final ConfigEpoch epoch;

    public CanaryRouter(ConfigEpoch epoch) {
        this.epoch = epoch;
    }

    public void registerVersion(ModelVersionSpec spec) {
        versions.put(spec.version(), spec);
        familyByVersion.put(spec.version(), spec.family());
        weights.computeIfAbsent(spec.family(), k -> new ConcurrentHashMap<>())
                .putIfAbsent(spec.version(), 0);
        traffic.computeIfAbsent(spec.family(), k -> new ConcurrentHashMap<>())
                .putIfAbsent(spec.version(), 0L);
    }

    /**
     * 启动时按配置播种权重，不推进配置纪元（尚未提供服务，无缓存可失效）。
     */
    public synchronized void seedWeights(String family, Map<String, Integer> newWeights) {
        if (!weights.containsKey(family)) {
            throw new RoutingException(GatewayErrorCode.VERSION_NOT_REGISTERED,
                    "model family '" + family + "' is not registered");
        }
        Map<String, Integer> target = weights.get(family);
        for (Map.Entry<String, Integer> e : newWeights.entrySet()) {
            if (!versions.containsKey(e.getKey())) {
                throw new RoutingException(GatewayErrorCode.VERSION_NOT_REGISTERED,
                        "cannot weight unregistered version '" + e.getKey() + "'");
            }
            target.put(e.getKey(), Math.max(0, e.getValue()));
        }
    }

    /**
     * 更新版本线灰度权重并使配置纪元递增（进而令旧缓存失效）。
     * 仅允许已注册版本；权重归一化到 0..100 相对比例。
     */
    public synchronized void updateWeights(String family, Map<String, Integer> newWeights) {
        if (family == null || !weights.containsKey(family)) {
            throw new RoutingException(GatewayErrorCode.VERSION_NOT_REGISTERED,
                    "model family '" + family + "' is not registered");
        }
        Map<String, Integer> target = weights.get(family);
        for (Map.Entry<String, Integer> e : newWeights.entrySet()) {
            if (!versions.containsKey(e.getKey())) {
                throw new RoutingException(GatewayErrorCode.VERSION_NOT_REGISTERED,
                        "cannot weight unregistered version '" + e.getKey() + "'");
            }
            int w = Math.max(0, e.getValue());
            target.put(e.getKey(), w);
        }
        epoch.bump();
    }

    public boolean isRegistered(String versionOrFamily) {
        return versions.containsKey(versionOrFamily) || weights.containsKey(versionOrFamily);
    }

    public ModelVersionSpec specOf(String version) {
        return versions.get(version);
    }

    /**
     * 路由解析。
     */
    public RoutingDecision route(String requestedVersion) {
        if (requestedVersion == null || requestedVersion.isBlank()) {
            throw new RoutingException(GatewayErrorCode.INVALID_REQUEST,
                    "requestedVersion must not be blank; gateway never falls back to a default");
        }

        // 1) 显式钉住具体版本
        ModelVersionSpec spec = versions.get(requestedVersion);
        if (spec != null) {
            int w = weights.get(spec.family()).getOrDefault(spec.version(), 0);
            RoutingDecision d = new RoutingDecision(spec.family(), spec.version(), true,
                    w == 0 ? "ZERO_WEIGHT_EXPLICIT" : "CANARY_ROUTE", w);
            recordTraffic(spec.family(), spec.version());
            return d;
        }

        // 2) 按版本线灰度加权
        Map<String, Integer> familyWeights = weights.get(requestedVersion);
        if (familyWeights != null) {
            RoutingDecision d = weightedPick(requestedVersion, familyWeights);
            recordTraffic(d.family(), d.version());
            return d;
        }

        // 3) 未注册：拒绝，不回退
        throw new RoutingException(GatewayErrorCode.VERSION_NOT_REGISTERED,
                "version or family '" + requestedVersion
                        + "' is not registered; no silent fallback to a default version");
    }

    private RoutingDecision weightedPick(String family, Map<String, Integer> familyWeights) {
        int total = familyWeights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            throw new RoutingException(GatewayErrorCode.NO_ENABLED_CANARY_VERSION,
                    "family '" + family + "' has all-zero canary weights; no version serves traffic");
        }
        int r = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        // 使用稳定顺序，保证边界可解释
        for (String v : new java.util.TreeSet<>(familyWeights.keySet())) {
            int w = familyWeights.get(v);
            acc += w;
            if (r < acc) {
                return new RoutingDecision(family, v, false, "CANARY_ROUTE", w);
            }
        }
        String last = new java.util.ArrayList<>(familyWeights.keySet())
                .get(familyWeights.size() - 1);
        return new RoutingDecision(family, last, false, "CANARY_ROUTE",
                familyWeights.get(last));
    }

    private void recordTraffic(String family, String version) {
        traffic.computeIfAbsent(family, k -> new ConcurrentHashMap<>())
                .merge(version, 1L, Long::sum);
    }

    /** 按 family 汇总实际流量计数，供管理接口观测灰度分配。 */
    public Map<String, Map<String, Long>> trafficCounts() {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (String family : new java.util.TreeSet<>(traffic.keySet())) {
            out.put(family, new LinkedHashMap<>(traffic.get(family)));
        }
        return out;
    }

    /** 当前生效权重快照。 */
    public Map<String, Map<String, Integer>> weightSnapshot() {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (String family : new java.util.TreeSet<>(weights.keySet())) {
            out.put(family, new LinkedHashMap<>(weights.get(family)));
        }
        return out;
    }

    public List<String> registeredVersions() {
        List<String> vs = new ArrayList<>(versions.keySet());
        Collections.sort(vs);
        return vs;
    }
}
