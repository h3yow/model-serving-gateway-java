package com.github.h3yow.modelservinggatewayjava.gateway.routing;

import com.github.h3yow.modelservinggatewayjava.gateway.model.GatewayErrorCode;

/**
 * 路由异常：版本未注册或不兼容时抛出，携带可区分的稳定错误码。
 */
public class RoutingException extends RuntimeException {

    private final GatewayErrorCode errorCode;

    public RoutingException(GatewayErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GatewayErrorCode getErrorCode() {
        return errorCode;
    }
}
