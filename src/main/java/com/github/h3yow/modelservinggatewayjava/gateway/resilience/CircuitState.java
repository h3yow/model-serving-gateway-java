package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 熔断器状态。
 */
public enum CircuitState {
    /** 关闭：正常放行。 */
    CLOSED,
    /** 打开：快速失败，流量被降级到其它健康后端。 */
    OPEN,
    /** 半开：冷却后放行少量试探请求，成功则恢复承载流量。 */
    HALF_OPEN
}
