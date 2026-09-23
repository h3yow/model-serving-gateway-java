package com.github.h3yow.modelservinggatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 网关配置。
 *
 * <pre>
 * gateway.backend-timeout-ms   后端调用超时（超时映射为 BACKEND_TIMEOUT）
 * gateway.failure-threshold    熔断连续失败阈值
 * gateway.open-state-ms        熔断打开后冷却时长，过后放一次试探请求
 * gateway.cache-ttl-ms         成功结果缓存有效期
 * gateway.backends[]           内置内存后端：id / latency-ms / failure
 * gateway.versions[]           已注册版本：model / version / backend / enabled / weight
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private long backendTimeoutMs = 200L;
    private int failureThreshold = 3;
    private long openStateMs = 2000L;
    private long cacheTtlMs = 60_000L;
    private List<BackendProps> backends = List.of();
    private List<VersionProps> versions = List.of();

    public long getBackendTimeoutMs() {
        return backendTimeoutMs;
    }

    public void setBackendTimeoutMs(long backendTimeoutMs) {
        this.backendTimeoutMs = backendTimeoutMs;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getOpenStateMs() {
        return openStateMs;
    }

    public void setOpenStateMs(long openStateMs) {
        this.openStateMs = openStateMs;
    }

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }

    public List<BackendProps> getBackends() {
        return backends;
    }

    public void setBackends(List<BackendProps> backends) {
        this.backends = backends;
    }

    public List<VersionProps> getVersions() {
        return versions;
    }

    public void setVersions(List<VersionProps> versions) {
        this.versions = versions;
    }

    public static class BackendProps {
        private String id;
        private long latencyMs = 0L;
        /** none / illegal / timeout / internal */
        private String failure = "none";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        public String getFailure() {
            return failure;
        }

        public void setFailure(String failure) {
            this.failure = failure;
        }
    }

    public static class VersionProps {
        private String model;
        private String version;
        private String backend;
        private boolean enabled = true;
        private int weight = 0;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }
    }
}
