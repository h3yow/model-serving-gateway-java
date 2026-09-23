package com.github.h3yow.modelservinggatewayjava.model;

/**
 * 评分结果。结果一旦产生即不可变；success=false 时携带可区分的 reason。
 *
 * <p>decision 字段记录该结果的完整判定依据（路由版本、命中后端、缓存命中等），
 * 保证结果可解释。</p>
 */
public record ScoreResponse(
        boolean success,
        String modelName,
        String modelVersion,
        String backendId,
        double score,
        RejectReason reason,
        String message,
        boolean cacheHit,
        long elapsedMs,
        String decision
) {
    public static ScoreResponse success(String modelName, String modelVersion, String backendId,
                                        double score, boolean cacheHit, long elapsedMs, String decision) {
        return new ScoreResponse(true, modelName, modelVersion, backendId, score,
                null, null, cacheHit, elapsedMs, decision);
    }

    public static ScoreResponse failure(String modelName, String modelVersion,
                                        RejectReason reason, String message, String decision) {
        return new ScoreResponse(false, modelName, modelVersion, null, 0.0,
                reason, message, false, 0L, decision);
    }
}
