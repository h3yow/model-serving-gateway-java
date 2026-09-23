package com.github.h3yow.modelservinggatewayjava.gateway.model;

import java.util.List;

/**
 * 批量评分请求。
 *
 * @param requests 评分请求列表
 */
public record BatchScoreRequest(List<ScoreRequest> requests) {
}
