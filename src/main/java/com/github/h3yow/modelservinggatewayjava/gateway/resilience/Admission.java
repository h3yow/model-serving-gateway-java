package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 一次请求对某后端的准入结果（{@link CircuitBreaker#acquire()} 返回）。
 */
public enum Admission {
    /** 正常放行：后端处于 CLOSED，按普通流量承载。 */
    ALLOW_NORMAL,
    /** 试探放行：冷却到期，调用方成为该后端唯一的在飞试探请求。 */
    ALLOW_PROBE,
    /** 拒绝：后端处于隔离 OPEN（冷却未到），或已有试探在飞，应走其它健康后端。 */
    DENY_ISOLATED
}
