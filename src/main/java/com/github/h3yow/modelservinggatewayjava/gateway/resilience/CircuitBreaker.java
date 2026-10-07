package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个后端的自愈熔断器（线程安全，内存实现）。
 *
 * <ul>
 *   <li>仅后端侧失败（超时/内部错误）计入失败计数；请求非法不计入；</li>
 *   <li>连续失败达到阈值 -> OPEN 隔离，期间 {@link #acquire()} 返回
 *       {@link Admission#DENY_ISOLATED}，请求改走其它健康后端；</li>
 *   <li>冷却到点后，首个到达的请求原子地抢到唯一试探权（OPEN -> HALF_OPEN，
 *       返回 {@link Admission#ALLOW_PROBE}）；同一时刻该后端最多一个试探请求，
 *       其余请求一律拒绝并继续走健康后端；</li>
 *   <li>试探成功 -> CLOSED，恢复正常承载，失败计数清零、退避轮次清零；</li>
 *   <li>试探失败 -> 重新 OPEN，下一次冷却按 {@link RecoveryPolicy} 逐级加长并封顶；</li>
 *   <li>只有真实请求（{@link #acquire()}）能驱动 OPEN -> HALF_OPEN 迁移；
 *       {@link #viewState()} 等只读观测方法绝不推进/打断恢复流程。</li>
 * </ul>
 *
 * <p>所有状态迁移都在 synchronized 临界区内完成并写入有界 {@link StateTimeline}，
 * 观测快照与状态迁移互斥，并发迁移/查询下不会错乱或丢记录。
 */
public class CircuitBreaker {

    private final String backendId;
    private final RecoveryPolicy policy;
    private final TimeSource timeSource;
    private final StateTimeline timeline;

    private CircuitState state = CircuitState.CLOSED;
    /** 最近连续后端侧失败数；试探成功恢复后清零。 */
    private int failures = 0;
    /** 已发生的隔离轮次：0=首次隔离；试探失败一次 +1，决定下一轮冷却时长。 */
    private int backoffAttempt = 0;
    /** 本次隔离开始时间（毫秒）。 */
    private long openedAtMs = 0L;
    /** 本次隔离生效的冷却时长（毫秒，退避后逐级加长、有上限）。 */
    private long cooldownMs = 0L;
    /** 是否已有试探请求在飞；同一时刻最多一个。 */
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    public CircuitBreaker(String backendId, int failureThreshold, long openDurationMs) {
        this(backendId, new RecoveryPolicy(failureThreshold, openDurationMs, 1.0d, openDurationMs),
                TimeSource.SYSTEM, 20);
    }

    public CircuitBreaker(String backendId, RecoveryPolicy policy, TimeSource timeSource,
                          int timelineCapacity) {
        this.backendId = backendId;
        this.policy = policy;
        this.timeSource = timeSource == null ? TimeSource.SYSTEM : timeSource;
        this.timeline = new StateTimeline(timelineCapacity);
    }

    /**
     * 请求准入：只有真实请求调用才会推进 OPEN -> HALF_OPEN 迁移。
     *
     * <p>OPEN 且冷却到期时，进入临界区的首个线程把状态原子切到 HALF_OPEN 并占用
     * 唯一试探名额；冷却未到、或试探名额已被占用时返回拒绝，调用方应继续尝试其它后端。
     */
    public Admission acquire() {
        synchronized (this) {
            long now = timeSource.nowMs();
            if (state == CircuitState.CLOSED) {
                return Admission.ALLOW_NORMAL;
            }
            if (state == CircuitState.HALF_OPEN) {
                // 已有唯一试探在飞：其余请求继续走健康后端
                return Admission.DENY_ISOLATED;
            }
            // OPEN：冷却到期才放一个试探
            if (now - openedAtMs >= cooldownMs) {
                if (probeInFlight.compareAndSet(false, true)) {
                    state = CircuitState.HALF_OPEN;
                    timeline.append(now, CircuitState.OPEN, CircuitState.HALF_OPEN,
                            TimelineReason.COOLDOWN_EXPIRED_PROBE,
                            "cooldownMs=" + cooldownMs + ",backoffAttempt=" + backoffAttempt);
                    return Admission.ALLOW_PROBE;
                }
            }
            return Admission.DENY_ISOLATED;
        }
    }

    /**
     * 记录一次被放行调用的成功结果。
     *
     * @param asProbe 该调用是否以试探身份执行（{@link Admission#ALLOW_PROBE}）；
     *                试探成功才执行“恢复承载 + 计数清零 + 退避清零”
     */
    public void recordSuccess(boolean asProbe) {
        synchronized (this) {
            if (asProbe) {
                try {
                    if (state == CircuitState.HALF_OPEN) {
                        CircuitState from = state;
                        state = CircuitState.CLOSED;
                        failures = 0;
                        backoffAttempt = 0;
                        cooldownMs = 0L;
                        openedAtMs = 0L;
                        timeline.append(timeSource.nowMs(), from, CircuitState.CLOSED,
                                TimelineReason.PROBE_SUCCEEDED,
                                "recovered to normal serving, failureCount reset to 0");
                    }
                } finally {
                    probeInFlight.set(false);
                }
            } else if (state == CircuitState.CLOSED) {
                failures = 0;
            }
        }
    }

    /**
     * 记录一次被放行调用的后端侧失败（超时/内部错误）。
     *
     * @param asProbe 该调用是否以试探身份执行；试探失败会重新隔离并加长下一轮冷却
     */
    public void recordFailure(boolean asProbe) {
        synchronized (this) {
            long now = timeSource.nowMs();
            if (asProbe) {
                try {
                    if (state == CircuitState.HALF_OPEN) {
                        long nextCooldown = policy.cooldownForAttempt(backoffAttempt + 1);
                        timeline.append(now, CircuitState.HALF_OPEN, CircuitState.OPEN,
                                TimelineReason.PROBE_FAILED,
                                "probe call failed; next cooldownMs=" + nextCooldown
                                        + ",backoffAttempt=" + (backoffAttempt + 1));
                        state = CircuitState.OPEN;
                        backoffAttempt++;
                        openedAtMs = now;
                        cooldownMs = nextCooldown;
                        failures++;
                    }
                } finally {
                    probeInFlight.set(false);
                }
                return;
            }
            if (state == CircuitState.CLOSED) {
                int n = ++failures;
                if (n >= policy.failureThreshold()) {
                    long firstCooldown = policy.cooldownForAttempt(0);
                    timeline.append(now, CircuitState.CLOSED, CircuitState.OPEN,
                            TimelineReason.FAILURE_THRESHOLD_REACHED,
                            "failureCount=" + n + " reached threshold "
                                    + policy.failureThreshold() + "; cooldownMs=" + firstCooldown);
                    state = CircuitState.OPEN;
                    backoffAttempt = 0;
                    openedAtMs = now;
                    cooldownMs = firstCooldown;
                }
            }
        }
    }

    /** 只读观测当前阶段：不推进任何状态迁移（冷却到期也保持 OPEN，直到真实请求试探）。 */
    public CircuitState viewState() {
        synchronized (this) {
            return state;
        }
    }

    /** 只读快照：阶段、失败计数、剩余隔离时间、试探占用、有界状态时间线。 */
    public BackendHealth health() {
        synchronized (this) {
            long remaining = 0L;
            if (state == CircuitState.OPEN) {
                remaining = Math.max(0L, cooldownMs - (timeSource.nowMs() - openedAtMs));
            }
            return new BackendHealth(backendId, state, failures, backoffAttempt,
                    openedAtMs, cooldownMs, remaining, probeInFlight.get(),
                    timeline.snapshot());
        }
    }

    public String backendId() {
        return backendId;
    }

    /**
     * 防御性释放试探名额：正常路径已由 recordSuccess/recordFailure 释放，
     * 此方法幂等，仅在调用方发生意外异常时兜底，避免“冷却结束却再也无法试探”。
     */
    public void ensureProbeReleased() {
        probeInFlight.set(false);
    }

    public int failureCount() {
        synchronized (this) {
            return failures;
        }
    }

    /** 手动复位为关闭状态（管理接口/测试支持），同样记录一条时间线。 */
    public void reset() {
        synchronized (this) {
            CircuitState from = state;
            failures = 0;
            backoffAttempt = 0;
            openedAtMs = 0L;
            cooldownMs = 0L;
            probeInFlight.set(false);
            state = CircuitState.CLOSED;
            if (from != CircuitState.CLOSED) {
                timeline.append(timeSource.nowMs(), from, CircuitState.CLOSED,
                        TimelineReason.MANUAL_RESET, "manual reset to CLOSED");
            }
        }
    }
}
