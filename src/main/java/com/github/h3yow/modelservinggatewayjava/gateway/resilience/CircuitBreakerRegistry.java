package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表：按 backendId 维护熔断器实例。
 */
public class CircuitBreakerRegistry {

    private final int failureThreshold;
    private final long openDurationMs;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(int failureThreshold, long openDurationMs) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
    }

    public CircuitBreaker forBackend(String backendId) {
        return breakers.computeIfAbsent(backendId,
                k -> new CircuitBreaker(k, failureThreshold, openDurationMs));
    }

    public Map<String, CircuitState> snapshotStates() {
        Map<String, CircuitState> out = new LinkedHashMap<>();
        breakers.keySet().stream().sorted().forEach(id ->
                out.put(id, breakers.get(id).state()));
        return out;
    }

    public void resetAll() {
        breakers.values().forEach(CircuitBreaker::reset);
    }
}
