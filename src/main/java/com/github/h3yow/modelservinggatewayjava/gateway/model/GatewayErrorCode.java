package com.github.h3yow.modelservinggatewayjava.gateway.model;

/**
 * 网关注一的稳定错误码。不同失败原因映射到不同错误码，保证可区分、可断言。
 */
public enum GatewayErrorCode {

    /** 请求本身非法（空请求、特征缺失等），不可重试，不参与熔断统计。 */
    INVALID_REQUEST,
    /** 请求的模型版本从未注册，绝不静默回退到默认版本。 */
    VERSION_NOT_REGISTERED,
    /** 版本已注册但与请求不兼容（如特征维度不匹配）。 */
    VERSION_INCOMPATIBLE,
    /** 后端处理超时（分级处理，区别于非法请求与内部错误）。 */
    BACKEND_TIMEOUT,
    /** 后端内部错误（分级处理，区别于超时）。 */
    BACKEND_INTERNAL_ERROR,
    /** 该版本所有后端均不可用：熔断打开且无其它健康后端可降级。 */
    ALL_BACKENDS_UNAVAILABLE,
    /** 请求的是版本线且全部版本灰度权重均为 0，无任何可承载流量的版本（不静默回退）。 */
    NO_ENABLED_CANARY_VERSION
}
