package com.github.h3yow.modelservinggatewayjava.exception;

import com.github.h3yow.modelservinggatewayjava.model.RejectReason;

/**
 * 网关业务异常，携带稳定的对外原因码。
 */
public class GatewayException extends RuntimeException {

    private final RejectReason reason;

    public GatewayException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
