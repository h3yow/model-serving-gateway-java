package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表：按 backendId 维护熔断器实例，统一持有退避策略与时间源。
 */
public class CircuitBreakerRegistry {

    private final RecoveryPolicy policy;
    private final TimeSource clock;
    private final int timelineCapacity;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** 兼容构造：固定冷却（不退避），系统墙钟。 */
    public CircuitBreakerRegistry(int failureThreshold, long openDurationMs) {
        this(new RecoveryPolicy(failureThreshold, openDurationMs, 1.0d, openDurationMs),
                TimeSource.SYSTEM, CircuitBreaker.DEFAULT_TIMELINE_CAPACITY);
    }

    public CircuitBreakerRegistry(RecoveryPolicy policy, TimeSource clock,
                                  int timelineCapacity) {
        this.policy = policy;
        this.clock = clock;
        this.timelineCapacity = timelineCapacity;
    }

    public CircuitBreaker forBackend(String backendId) {
        return breakers.computeIfAbsent(backendId,
                k -> new CircuitBreaker(k, policy, clock, timelineCapacity));
    }

    /** 各后端当前状态（只读，不推进任何状态迁移）。 */
    public Map<String, CircuitState> snapshotStates() {
        Map<String, CircuitState> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(id ->
                out.put(id, breakers.get(id).state()));
        return out;
    }

    /** 全部后端的只读健康快照（含剩余冷却、退避轮次、状态时间线）。 */
    public Map<String, BackendHealth> healthSnapshot() {
        Map<String, BackendHealth> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(id ->
                out.put(id, breakers.get(id).snapshot()));
        return out;
    }

    /** 单个后端的只读健康快照；未注册过返回 null。 */
    public BackendHealth healthOf(String backendId) {
        CircuitBreaker cb = breakers.get(backendId);
        return cb == null ? null : cb.snapshot();
    }

    public void resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
    }
}
