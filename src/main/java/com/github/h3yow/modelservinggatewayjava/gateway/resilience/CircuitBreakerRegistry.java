package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表：按 backendId 维护熔断器实例（线程安全）。
 *
 * <p>所有快照方法只读：不推进 OPEN -> HALF_OPEN 迁移、不触碰失败计数与流量统计。
 */
public class CircuitBreakerRegistry {

    private final RecoveryPolicy policy;
    private final TimeSource timeSource;
    private final int timelineCapacity;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(int failureThreshold, long openDurationMs) {
        this(new RecoveryPolicy(failureThreshold, openDurationMs, 1.0d, openDurationMs),
                TimeSource.SYSTEM, 20);
    }

    public CircuitBreakerRegistry(RecoveryPolicy policy, TimeSource timeSource,
                                  int timelineCapacity) {
        this.policy = policy;
        this.timeSource = timeSource == null ? TimeSource.SYSTEM : timeSource;
        this.timelineCapacity = Math.max(1, timelineCapacity);
    }

    public CircuitBreaker forBackend(String backendId) {
        return breakers.computeIfAbsent(backendId,
                k -> new CircuitBreaker(k, policy, timeSource, timelineCapacity));
    }

    /** 只读：各后端当前阶段（不触发冷却迁移）。 */
    public Map<String, CircuitState> snapshotStates() {
        Map<String, CircuitState> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(id ->
                out.put(id, breakers.get(id).viewState()));
        return out;
    }

    /** 只读：各后端完整健康快照（阶段/剩余隔离/试探占用/状态时间线）。 */
    public Map<String, BackendHealth> snapshotHealth() {
        Map<String, BackendHealth> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(id ->
                out.put(id, breakers.get(id).health()));
        return out;
    }

    public void resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
    }
}
