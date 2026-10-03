package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 熔断器自愈策略配置。
 *
 * @param failureThreshold   连续后端侧失败达到该次数后隔离（OPEN）
 * @param initialBackoffMs   首次隔离的冷却时长（毫秒）
 * @param backoffMultiplier  每次试探失败后冷却时长的退避乘数（>=1）
 * @param maxBackoffMs       冷却时长上限（毫秒），退避不会超过该值
 * @param timelineCapacity   每后端保留的状态变化时间线条数上限
 */
public record CircuitBreakerConfig(int failureThreshold,
                                   long initialBackoffMs,
                                   double backoffMultiplier,
                                   long maxBackoffMs,
                                   int timelineCapacity) {
    public CircuitBreakerConfig {
        failureThreshold = Math.max(1, failureThreshold);
        initialBackoffMs = Math.max(0L, initialBackoffMs);
        maxBackoffMs = Math.max(initialBackoffMs, maxBackoffMs);
        backoffMultiplier = Math.max(1.0d, backoffMultiplier);
        timelineCapacity = Math.max(1, timelineCapacity);
    }
}
