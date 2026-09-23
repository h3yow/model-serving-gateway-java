package com.github.h3yow.modelservinggatewayjava.exception;

import com.github.h3yow.modelservinggatewayjava.model.RejectReason;

/**
 * 后端异常，按类别映射为不同的对外结果。
 */
public class BackendException extends GatewayException {
    public BackendException(RejectReason reason, String message) {
        super(reason, message);
    }
}
