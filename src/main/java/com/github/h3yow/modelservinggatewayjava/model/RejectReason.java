package com.github.h3yow.modelservinggatewayjava.model;

/**
 * 网关对外的可区分拒绝/失败原因（稳定错误码）。
 */
public enum RejectReason {
    INVALID_REQUEST,
    VERSION_NOT_REGISTERED,
    VERSION_INCOMPATIBLE,
    VERSION_DISABLED,
    NO_CANDIDATE_VERSION,
    BACKEND_ILLEGAL_ARGUMENT,
    BACKEND_TIMEOUT,
    BACKEND_INTERNAL_ERROR,
    CIRCUIT_OPEN
}
