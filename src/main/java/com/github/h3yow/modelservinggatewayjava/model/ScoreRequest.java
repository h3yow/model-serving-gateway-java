package com.github.h3yow.modelservinggatewayjava.model;

import java.util.Map;

/**
 * 评分请求。modelVersion 为 null 时按灰度权重路由；非 null 时必须精确命中已注册版本。
 */
public record ScoreRequest(
        String modelName,
        String modelVersion,
        String featureHash,
        Map<String, Object> features
) {
}
