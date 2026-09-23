package com.github.h3yow.modelservinggatewayjava.gateway.backend;

/**
 * 后端失败模式（测试/管理接口注入用）。
 */
public enum FailureMode {
    /** 正常服务。 */
    NONE,
    /** 模拟处理超时。 */
    TIMEOUT,
    /** 模拟后端内部错误。 */
    INTERNAL_ERROR,
    /** 模拟将请求判定为非法（不参与熔断统计）。 */
    INVALID_REQUEST
}
