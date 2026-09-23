package com.github.h3yow.modelservinggatewayjava.routing;

import com.github.h3yow.modelservinggatewayjava.model.ModelVersion;

/**
 * 一次路由判定的结果（命中版本 + 判定依据，用于可解释日志）。
 */
public record RouteDecision(ModelVersion version, String basis) {
}
