package com.github.h3yow.modelservinggatewayjava.gateway.routing;

import java.util.Map;

/**
 * 灰度权重配置：版本线 -> (具体版本 -> 权重 0..100)。
 * 权重为 0 的版本不接收灰度流量，但显式钉住该版本的请求仍被服务。
 */
public class CanaryWeights {

    private Map<String, Map<String, Integer>> weightsByFamily;

    public Map<String, Map<String, Integer>> getWeightsByFamily() {
        return weightsByFamily;
    }

    public void setWeightsByFamily(Map<String, Map<String, Integer>> weightsByFamily) {
        this.weightsByFamily = weightsByFamily;
    }
}
