package com.github.h3yow.modelservinggatewayjava.gateway.routing;

/**
 * 路由决策结果。
 *
 * @param family      版本线
 * @param version     命中的具体版本
 * @param pinned      是否由调用方显式钉住具体版本（true 表示非灰度随机分配）
 * @param reason      判定依据（CANARY_ROUTE / ZERO_WEIGHT_EXPLICIT）
 * @param weightUsed  命中版本当前权重（用于日志与可解释性）
 */
public record RoutingDecision(String family, String version, boolean pinned,
                              String reason, int weightUsed) {
}
