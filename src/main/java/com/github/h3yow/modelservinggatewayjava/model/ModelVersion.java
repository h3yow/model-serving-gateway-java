package com.github.h3yow.modelservinggatewayjava.model;

/**
 * 已注册模型版本及其后端归属。
 */
public record ModelVersion(
        String modelName,
        String version,
        String backendId,
        boolean enabled,
        int weight
) {
}
