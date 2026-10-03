package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 熔断器对一次真实流量的准入判定（无副作用观测不会产生该对象）。
 *
 * @param allowed    是否放行到该后端
 * @param probe      放行的请求是否为恢复试探请求（同一后端同一时刻最多一个）
 * @param skipReason 被跳过时的原因（{@code null} 表示放行）
 */
public record AdmissionResult(boolean allowed, boolean probe, String skipReason) {

    public static final String SKIP_ISOLATED = "CIRCUIT_OPEN_SKIPPED";
    public static final String SKIP_PROBE_IN_FLIGHT = "RECOVERY_PROBE_IN_FLIGHT_SKIPPED";

    public static AdmissionResult passNormal() {
        return new AdmissionResult(true, false, null);
    }

    public static AdmissionResult passProbe() {
        return new AdmissionResult(true, true, null);
    }

    public static AdmissionResult reject(String reason) {
        return new AdmissionResult(false, false, reason);
    }
}
