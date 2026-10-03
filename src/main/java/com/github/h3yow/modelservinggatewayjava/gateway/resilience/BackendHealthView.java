package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.List;

/**
 * 单个后端的只读健康快照。由纯读方法生成：连续查、并发查、高频查都不会
 * 驱动或打断恢复流程，也不会被计入失败/真实流量统计。
 *
 * @param backendId           后端 id
 * @param state               熔断器内部权威状态（CLOSED/OPEN/HALF_OPEN）
 * @param phase               面向运维的阶段：NORMAL / ISOLATED / ISOLATED_COOLDOWN_DUE / PROBING
 * @param failureCount        当前连续失败计数
 * @param probeAttempt        第几次恢复试探（0 表示当前未处于试探）
 * @param currentBackoffMs    当前隔离冷却时长（已含逐级退避）
 * @param remainingIsolationMs 隔离剩余毫秒数；冷却已到但试探尚未被流量触发时为 0
 * @param probeEligible       冷却是否已结束、下一个真实请求将拿到唯一试探权
 * @param probeInFlight       当前是否已有一个试探请求在执行
 * @param timeline            最近的状态变化（旧 -> 新，最多 timelineCapacity 条）
 */
public record BackendHealthView(String backendId,
                                CircuitState state,
                                String phase,
                                int failureCount,
                                int probeAttempt,
                                long currentBackoffMs,
                                long remainingIsolationMs,
                                boolean probeEligible,
                                boolean probeInFlight,
                                List<StateTransitionEvent> timeline) {
}
