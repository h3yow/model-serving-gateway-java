package com.github.h3yow.modelservinggatewayjava.version;

import com.github.h3yow.modelservinggatewayjava.model.ModelVersion;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型版本注册表。
 *
 * <p>维护每个模型名下已注册版本及其 enabled/weight 状态；任何权重或启用状态变更都会使
 * {@link #configVersion()} 递增，作为缓存失效依据（模型版本升级或权重调整后缓存必须失效）。</p>
 */
public class VersionRegistry {

    /** key = modelName + "|" + version */
    private final Map<String, ModelVersion> versions = new ConcurrentHashMap<>();
    private final AtomicLong configVersion = new AtomicLong(1);

    public VersionRegistry(List<ModelVersion> initial) {
        if (initial != null) {
            initial.forEach(this::register);
        }
    }

    private String key(String modelName, String version) {
        return modelName + "|" + version;
    }

    public void register(ModelVersion v) {
        versions.put(key(v.modelName(), v.version()), v);
        configVersion.incrementAndGet();
    }

    /** 精确查找已注册版本；未注册返回 null（由上层以 VERSION_NOT_REGISTERED 拒绝）。 */
    public ModelVersion find(String modelName, String version) {
        return versions.get(key(modelName, version));
    }

    /** 某模型下全部已启用版本（快照），作为灰度候选。 */
    public List<ModelVersion> candidates(String modelName) {
        return versions.values().stream()
                .filter(v -> Objects.equals(v.modelName(), modelName))
                .filter(ModelVersion::enabled)
                .sorted((a, b) -> a.version().compareTo(b.version()))
                .toList();
    }

    /** 运行时调整灰度权重；变更后配置版本递增以触发缓存失效。 */
    public void updateWeight(String modelName, String version, int weight) {
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be >= 0");
        }
        String k = key(modelName, version);
        versions.compute(k, (ignored, old) -> {
            if (old == null) {
                throw new IllegalArgumentException("version not registered: " + k);
            }
            return new ModelVersion(old.modelName(), old.version(), old.backendId(),
                    old.enabled(), weight);
        });
        configVersion.incrementAndGet();
    }

    /** 启用/停用版本（模拟版本上下线）；停用后不再承接灰度流量。 */
    public void setEnabled(String modelName, String version, boolean enabled) {
        String k = key(modelName, version);
        versions.compute(k, (ignored, old) -> {
            if (old == null) {
                throw new IllegalArgumentException("version not registered: " + k);
            }
            return new ModelVersion(old.modelName(), old.version(), old.backendId(),
                    enabled, old.weight());
        });
        configVersion.incrementAndGet();
    }

    public long configVersion() {
        return configVersion.get();
    }
}
