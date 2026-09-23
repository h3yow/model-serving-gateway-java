package com.github.h3yow.modelservinggatewayjava.gateway.backend;

import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;

/**
 * 推理后端抽象。内置实现为内存模拟，便于稳定复现超时/内部错误等异常。
 */
public interface InferenceBackend {

    /** 后端唯一标识。 */
    String id();

    /** 后端类型。 */
    BackendType type();

    /** 该后端承载的模型版本。 */
    String version();

    /**
     * 执行评分。实现需在超时时抛出 {@link GatewayErrorCode#BACKEND_TIMEOUT}，
     * 非法请求抛出 {@link GatewayErrorCode#INVALID_REQUEST}，
     * 内部故障抛出 {@link GatewayErrorCode#BACKEND_INTERNAL_ERROR}。
     */
    double score(ScoreRequest request);
}
