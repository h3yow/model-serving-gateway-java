package com.github.h3yow.modelservinggatewayjava.gateway.routing;

/**
 * 模型版本规格：描述一个具体已注册版本及其兼容性要求。
 *
 * @param version              具体版本，如 ml-score-v2
 * @param family               所属模型版本线（灰度在同一 family 内进行），如 ml-score
 * @param expectedFeatureDim   该版本要求的特征维度；维度不符视为不兼容
 */
public record ModelVersionSpec(String version, String family, int expectedFeatureDim) {
}
