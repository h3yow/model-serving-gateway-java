package com.github.h3yow.modelservinggatewayjava.gateway.model;

/**
 * 单条评分结果（成功或失败统一封装，失败时 result 为 null 且携带稳定错误码与原因）。
 *
 * @param requestId       对应请求的标识
 * @param success         是否成功
 * @param result          评分值，失败时为 null
 * @param servedVersion   实际命中的模型版本（灰度路由可能与请求版本不同，但同属一条版本线）
 * @param backendId       实际服务的推理后端
 * @param fromCache       是否命中缓存
 * @param decisionReason  判定依据（路由/降级/熔断/缓存等，保证结果可解释）
 * @param errorCode       稳定错误码，失败时非空
 * @param errorMessage    可读失败原因
 */
public record ScoreResult(String requestId, boolean success, Double result,
                          String servedVersion, String backendId, boolean fromCache,
                          String decisionReason, String errorCode, String errorMessage) {

    public static ScoreResult ok(String requestId, double result, String servedVersion,
                                 String backendId, boolean fromCache, String decisionReason) {
        return new ScoreResult(requestId, true, result, servedVersion, backendId,
                fromCache, decisionReason, null, null);
    }

    public static ScoreResult error(String requestId, String errorCode, String errorMessage,
                                    String decisionReason) {
        return new ScoreResult(requestId, false, null, null, null, false,
                decisionReason, errorCode, errorMessage);
    }
}
