package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表：按 backendId 维护熔断器实例（线程安全）。
 * 所有快照方法都是纯读，不会推进或打断任何后端的恢复流程。
 */
public class CircuitBreakerRegistry {

    private final CircuitBreakerConfig config;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(int failureThreshold, long openDurationMs) {
        this(new CircuitBreakerConfig(failureThreshold, openDurationMs,
                1.0d, openDurationMs, 20));
    }

    public CircuitBreakerRegistry(CircuitBreakerConfig config) {
        this.config = config;
    }

    public CircuitBreaker forBackend(String backendId) {
        return breakers.computeIfAbsent(backendId,
                k -> new CircuitBreaker(k, config));
    }

    /** 纯读：当前权威熔断状态（不会触发 OPEN -> HALF_OPEN 迁移）。 */
    public Map<String, CircuitState> snapshotStates() {
        Map<String, CircuitState> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(id ->
                out.put(id, breakers.get(id).state()));
        return out;
    }

    /** 纯读：各后端健康/恢复进度快照（阶段、剩余隔离时长、试探信息、状态时间线）。 */
    public Map<String, BackendHealthView> snapshotHealth() {
        Map<String, BackendHealthView> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(id ->
                out.put(id, breakers.get(id).view()));
        return out;
    }

    /** 纯读：单个后端健康快照；该后端从未被熔断器见过时返回 null。 */
    public BackendHealthView healthOf(String backendId) {
        CircuitBreaker cb = breakers.get(backendId);
        return cb == null ? null : cb.view();
    }

    public void resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
    }
}
