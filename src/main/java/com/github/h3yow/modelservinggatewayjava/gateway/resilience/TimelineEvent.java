package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 后端状态时间线上的一条关键状态变化（不可变）。
 *
 * @param seq          单调递增序号（从 1 开始）
 * @param timestampMs  发生时间（毫秒，来自 {@link TimeSource}）
 * @param from         变化前阶段
 * @param to           变化后阶段
 * @param reason       变化原因（连续失败阈值 / 试探失败 / 试探成功 / 手动复位）
 * @param detail       可读细节（失败计数、生效冷却时长等）
 */
public record TimelineEvent(long seq, long timestampMs, CircuitState from, CircuitState to,
                            String reason, String detail) {
}
