package com.github.h3yow.modelservinggatewayjava.gateway.model;

import java.util.List;

/**
 * 单条评分请求。
 *
 * @param requestId       调用方提供的请求标识（用于日志关联）
 * @param requestedVersion 调用方期望的模型版本（必填，网关不做静默默认回退）
 * @param features        特征向量；不同模型版本对维度有兼容性要求
 */
public record ScoreRequest(String requestId, String requestedVersion, List<Double> features) {
}
