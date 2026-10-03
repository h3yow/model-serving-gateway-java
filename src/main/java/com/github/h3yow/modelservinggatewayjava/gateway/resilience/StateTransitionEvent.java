package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 后端状态时间线上的一条关键状态变化记录（不可变）。
 *
 * @param timestampMs  发生时间（System.currentTimeMillis()）
 * @param from         变化前阶段
 * @param to           变化后阶段
 * @param reason       变化原因（如 FAILURE_THRESHOLD_REACHED / RECOVERY_PROBE_GRANTED）
 * @param failureCount 发生时的连续失败次数
 * @param backoffMs    变化后生效的冷却时长（毫秒；无隔离语义时为 0）
 * @param probeAttempt 第几次恢复试探（首次试探为 1，正常承载阶段为 0）
 */
public record StateTransitionEvent(long timestampMs,
                                   CircuitState from,
                                   CircuitState to,
                                   String reason,
                                   int failureCount,
                                   long backoffMs,
                                   int probeAttempt) {
}
