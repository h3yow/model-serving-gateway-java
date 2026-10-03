package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.List;

/**
 * 单个后端的只读自愈健康快照（观测接口使用，不触发任何状态迁移）。
 *
 * @param backendId          后端 id
 * @param state              当前阶段：CLOSED / OPEN / HALF_OPEN
 * @param failureCount       最近连续失败计数（恢复后清零）
 * @param backoffAttempt     当前退避轮次（第几次冷却；0 表示尚未隔离过）
 * @param openedAtMs         本次隔离开始时间（毫秒；未隔离为 0）
 * @param cooldownMs         本次隔离生效的冷却时长（毫秒）
 * @param remainingCooldownMs 隔离还要持续多久（毫秒；非 OPEN 为 0，不小于 0）
 * @param probeInFlight      是否已有一个试探请求在执行中
 * @param timeline           最近若干次关键状态变化（按时间正序，有上限）
 */
public record BackendHealth(String backendId, CircuitState state, int failureCount,
                            int backoffAttempt, long openedAtMs, long cooldownMs,
                            long remainingCooldownMs, boolean probeInFlight,
                            List<TimelineEvent> timeline) {
}
