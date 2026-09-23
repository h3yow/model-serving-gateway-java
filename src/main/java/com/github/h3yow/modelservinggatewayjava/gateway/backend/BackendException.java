package com.github.h3yow.modelservinggatewayjava.gateway.backend;

import com.github.h3yow.modelservinggatewayjava.gateway.model.GatewayErrorCode;

/**
 * 后端调用异常，携带分级错误码：超时 / 非法请求 / 内部错误互相区分。
 */
public class BackendException extends RuntimeException {

    private final GatewayErrorCode errorCode;

    public BackendException(GatewayErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GatewayErrorCode getErrorCode() {
        return errorCode;
    }
}
