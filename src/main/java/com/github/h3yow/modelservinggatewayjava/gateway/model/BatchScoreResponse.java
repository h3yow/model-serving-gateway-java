package com.github.h3yow.modelservinggatewayjava.gateway.model;

import java.util.List;

/**
 * 批量评分响应：已完成的结果不会因后续降级/熔断被改写。
 *
 * @param results       与请求顺序一一对应的结果
 * @param cacheEpoch    本次服务使用的缓存/配置纪元
 * @param cacheHits     本批次命中缓存的条数
 * @param cacheComputed 本批次实际计算的条数
 */
public record BatchScoreResponse(List<ScoreResult> results, long cacheEpoch,
                                 int cacheHits, int cacheComputed) {
}
