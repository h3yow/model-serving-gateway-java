package com.github.h3yow.modelservinggatewayjava.routing;

import com.github.h3yow.modelservinggatewayjava.model.ModelVersion;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 加权灰度路由器。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>仅在 enabled 候选版本中按 weight 比例分配流量；权重为 0 的版本不承接流量；</li>
 *   <li>所有候选权重之和为 0 时无法路由（上层返回 NO_CANDIDATE_VERSION）；</li>
 *   <li>累计每个版本实际命中次数，{@link #trafficSnapshot()} 可观测真实流量分配；</li>
 *   <li>支持 pinnedPoint 确定性选点（测试可复现），生产路径使用随机落点。</li>
 * </ul>
 */
public class WeightedRouter {

    /** key = modelName|version -> 实际命中次数 */
    private final Map<String, AtomicLong> routed = new ConcurrentHashMap<>();
    private final AtomicLong totalRouted = new AtomicLong();

    /**
     * @param candidates  已启用候选版本（非空列表）
     * @param pinnedPoint 非 null 时使用该确定性落点 [0,totalWeight)；null 时随机
     */
    public RouteDecision route(List<ModelVersion> candidates, Long pinnedPoint) {
        int totalWeight = candidates.stream().mapToInt(ModelVersion::weight).sum();
        if (totalWeight <= 0) {
            return null;
        }
        int point;
        if (pinnedPoint != null) {
            point = Math.floorMod(pinnedPoint, totalWeight);
        } else {
            point = ThreadLocalRandom.current().nextInt(totalWeight);
        }

        int cumulative = 0;
        ModelVersion picked = null;
        for (ModelVersion v : candidates) {
            cumulative += v.weight();
            if (point < cumulative) {
                picked = v;
                break;
            }
        }
        if (picked == null) {
            picked = candidates.get(candidates.size() - 1);
        }

        String key = picked.modelName() + "|" + picked.version();
        routed.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        totalRouted.incrementAndGet();

        String basis = String.format(
                "gray-route point=%d/%d picked=%s(weight=%d, backend=%s)",
                point, totalWeight, picked.version(), picked.weight(), picked.backendId());
        return new RouteDecision(picked, basis);
    }

    /** 实际流量分布快照，例如 "v1=700(70.0%), v2=300(30.0%), total=1000"。 */
    public String trafficSnapshot() {
        long total = totalRouted.get();
        StringBuilder sb = new StringBuilder();
        routed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    long n = e.getValue().get();
                    double pct = total == 0 ? 0.0 : (n * 100.0 / total);
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(e.getKey()).append('=').append(n)
                            .append(String.format("(%.1f%%)", pct));
                });
        sb.append(", total=").append(total);
        return sb.toString();
    }

    public long routedCount(String modelName, String version) {
        AtomicLong c = routed.get(modelName + "|" + version);
        return c == null ? 0 : c.get();
    }

    public void resetStats() {
        routed.clear();
        totalRouted.set(0);
    }
}
