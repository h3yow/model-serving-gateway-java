package com.github.h3yow.modelservinggatewayjava.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关可配置项（application.properties 中 gateway.* 前缀）。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 已注册版本：version -> {family, expected-feature-dim}。 */
    private Map<String, VersionProps> versions = new LinkedHashMap<>();

    /** 后端实例：每项 {id, type, version}。 */
    private List<BackendProps> backends = new ArrayList<>();

    /** 灰度权重：family -> (version -> 权重 0..100)。 */
    private Map<String, Map<String, Integer>> weights = new LinkedHashMap<>();

    private CircuitProps circuit = new CircuitProps();

    private CacheProps cache = new CacheProps();

    public Map<String, VersionProps> getVersions() {
        return versions;
    }

    public void setVersions(Map<String, VersionProps> versions) {
        this.versions = versions;
    }

    public List<BackendProps> getBackends() {
        return backends;
    }

    public void setBackends(List<BackendProps> backends) {
        this.backends = backends;
    }

    public Map<String, Map<String, Integer>> getWeights() {
        return weights;
    }

    public void setWeights(Map<String, Map<String, Integer>> weights) {
        this.weights = weights;
    }

    public CircuitProps getCircuit() {
        return circuit;
    }

    public void setCircuit(CircuitProps circuit) {
        this.circuit = circuit;
    }

    public CacheProps getCache() {
        return cache;
    }

    public void setCache(CacheProps cache) {
        this.cache = cache;
    }

    public static class VersionProps {
        private String family;
        private int expectedFeatureDim;

        public String getFamily() {
            return family;
        }

        public void setFamily(String family) {
            this.family = family;
        }

        public int getExpectedFeatureDim() {
            return expectedFeatureDim;
        }

        public void setExpectedFeatureDim(int expectedFeatureDim) {
            this.expectedFeatureDim = expectedFeatureDim;
        }
    }

    public static class BackendProps {
        private String id;
        private String type;
        private String version;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    public static class CircuitProps {
        /** 连续失败多少次后熔断打开。 */
        private int failureThreshold = 3;
        /** 熔断打开持续时间（毫秒），结束后进入半开试探。 */
        private long openDurationMs = 5000;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenDurationMs() {
            return openDurationMs;
        }

        public void setOpenDurationMs(long openDurationMs) {
            this.openDurationMs = openDurationMs;
        }
    }

    public static class CacheProps {
        /** 缓存有效期（毫秒）。 */
        private long ttlMs = 60000;

        public long getTtlMs() {
            return ttlMs;
        }

        public void setTtlMs(long ttlMs) {
            this.ttlMs = ttlMs;
        }
    }
}
