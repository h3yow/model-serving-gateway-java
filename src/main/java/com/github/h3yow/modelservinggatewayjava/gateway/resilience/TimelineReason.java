package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 状态时间线 {@link TimelineEvent#reason()} 的稳定取值。
 */
public final class TimelineReason {

    private TimelineReason() {
    }

    /** 连续失败达到阈值：CLOSED -> OPEN（首次隔离）。 */
    public static final String FAILURE_THRESHOLD_REACHED = "FAILURE_THRESHOLD_REACHED";
    /** 冷却到期，首个请求抢到试探权：OPEN -> HALF_OPEN。 */
    public static final String COOLDOWN_EXPIRED_PROBE = "COOLDOWN_EXPIRED_PROBE";
    /** 试探成功：HALF_OPEN -> CLOSED，恢复正常承载，失败计数清零。 */
    public static final String PROBE_SUCCEEDED = "PROBE_SUCCEEDED";
    /** 试探失败：HALF_OPEN -> OPEN，按退避策略进入下一轮更长冷却。 */
    public static final String PROBE_FAILED = "PROBE_FAILED";
    /** 手动复位（管理接口/测试）：-> CLOSED。 */
    public static final String MANUAL_RESET = "MANUAL_RESET";
}
