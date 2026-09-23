package com.github.h3yow.modelservinggatewayjava.gateway.model;

/**
 * 判定依据常量，写入 {@link ScoreResult#decisionReason()} 与测试日志，保证可解释。
 */
public final class DecisionReason {

    private DecisionReason() {
    }

    /** 按配置灰度权重直接路由到某版本。 */
    public static final String CANARY_ROUTE = "CANARY_ROUTE";
    /** 首选后端异常，降级到同版本线的其它健康后端。 */
    public static final String FALLBACK_HEALTHY_BACKEND = "FALLBACK_HEALTHY_BACKEND";
    /** 命中有效期内的缓存，未重复计算。 */
    public static final String CACHE_HIT = "CACHE_HIT";
    /** 并发请求合并：等待首个请求的计算结果，自身未重复计算。 */
    public static final String CONCURRENT_DEDUP = "CONCURRENT_DEDUP";
    /** 缓存未命中，实际调用后端计算。 */
    public static final String CACHE_MISS_COMPUTED = "CACHE_MISS_COMPUTED";
    /** 后端熔断打开，跳过该后端。 */
    public static final String CIRCUIT_OPEN_SKIPPED = "CIRCUIT_OPEN_SKIPPED";
    /** 版本灰度权重为 0，但仍对显式版本请求提供服务（区别于未注册）。 */
    public static final String ZERO_WEIGHT_EXPLICIT = "ZERO_WEIGHT_EXPLICIT";
}
