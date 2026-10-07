package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个后端的熔断器（线程安全，内存实现），带自愈退避与只读观测。
 *
 * <ul>
 *   <li>仅后端侧失败（超时/内部错误）计入失败计数；请求非法不计入；</li>
 *   <li>连续失败达到阈值 -&gt; OPEN，期间 {@link #tryAcquire()} 快速失败；</li>
 *   <li>OPEN 冷却到期后，首个请求原子抢到唯一试探权（HALF_OPEN），
 *       同一时刻最多一个试探请求，其余请求继续被拒绝、走健康后端；</li>
 *   <li>试探成功 -&gt; CLOSED 恢复承载，失败计数与退避轮次清零；</li>
 *   <li>试探失败 -&gt; 重新 OPEN，下一次冷却按 {@link RecoveryPolicy} 逐级加长并封顶；</li>
 *   <li>{@link #snapshot()} 纯只读：不推进状态、不计数、可并发反复调用且结果一致。</li>
 * </ul>
 */
public class CircuitBreaker {

    /** 默认状态时间线容量（每后端保留最近多少条关键状态变化）。 */
    public static final int DEFAULT_TIMELINE_CAPACITY = 32;

    /** 一次请求准入的结果。 */
    public enum Permit {
        /** 正常放行（熔断器关闭，常规承载）。 */
        NORMAL,
        /** 抢到冷却后的唯一试探权（本请求即试探请求）。 */
        PROBE,
        /** 被拒绝（隔离冷却中，或已有试探在飞）。 */
        REJECTED
    }

    private final String backendId;
    private final RecoveryPolicy policy;
    private final TimeSource clock;
    private final StateTimeline timeline;

    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0L);
    /** 本次隔离生效的冷却时长（毫秒）。 */
    private final AtomicLong currentCooldownMs = new AtomicLong(0L);
    /** 退避轮次：第几次隔离（0 表示首次），试探失败递增，恢复后清零。 */
    private final AtomicInteger backoffAttempt = new AtomicInteger(0);

    public CircuitBreaker(String backendId, int failureThreshold, long openDurationMs) {
        this(backendId,
                new RecoveryPolicy(failureThreshold, openDurationMs, 1.0d, openDurationMs),
                TimeSource.SYSTEM, DEFAULT_TIMELINE_CAPACITY);
    }

    public CircuitBreaker(String backendId, RecoveryPolicy policy, TimeSource clock,
                          int timelineCapacity) {
        this.backendId = backendId;
        this.policy = policy;
        this.clock = clock;
        this.timeline = new StateTimeline(timelineCapacity);
    }

    /**
     * 请求准入（写路径，仅评分主链路调用）。
     * OPEN 冷却到期时，首个调用原子抢到 HALF_OPEN 试探权（返回 {@link Permit#PROBE}），
     * 其余并发调用仍返回 {@link Permit#REJECTED}。
     */
    public Permit tryAcquire() {
        CircuitState s = state.get();
        if (s == CircuitState.CLOSED) {
            return Permit.NORMAL;
        }
        if (s == CircuitState.HALF_OPEN) {
            return Permit.REJECTED; // 已有试探在飞，同后端同时刻最多一个试探
        }
        // OPEN：冷却到期才允许一个试探
        if (clock.nowMs() - openedAtMs.get() >= currentCooldownMs.get()
                && state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
            timeline.append(clock.nowMs(), CircuitState.OPEN, CircuitState.HALF_OPEN,
                    "冷却到期", "cooldownMs=" + currentCooldownMs.get()
                            + ",backoffAttempt=" + backoffAttempt.get());
            return Permit.PROBE;
        }
        return Permit.REJECTED;
    }

    /**
     * 记录一次成功。
     *
     * @return true 表示本次是试探成功（熔断器由此恢复关闭），调用方可据此标注「恢复后正常承载」
     */
    public boolean onSuccess() {
        boolean wasProbe = state.get() == CircuitState.HALF_OPEN;
        failures.set(0);
        if (wasProbe) {
            backoffAttempt.set(0);
            currentCooldownMs.set(0L);
            state.set(CircuitState.CLOSED);
            timeline.append(clock.nowMs(), CircuitState.HALF_OPEN, CircuitState.CLOSED,
                    "试探成功", "恢复正常承载，失败计数与退避轮次清零");
        }
        return wasProbe;
    }

    /** 记录一次后端侧失败（超时/内部错误）。 */
    public void onFailure() {
        if (state.get() == CircuitState.HALF_OPEN) {
            // 试探失败：重新隔离，冷却逐级加长并封顶（仅试探线程可达，无竞争）
            int attempt = backoffAttempt.incrementAndGet();
            long cooldown = policy.cooldownForAttempt(attempt);
            currentCooldownMs.set(cooldown);
            openedAtMs.set(clock.nowMs());
            state.set(CircuitState.OPEN);
            timeline.append(openedAtMs.get(), CircuitState.HALF_OPEN, CircuitState.OPEN,
                    "试探失败", "下一次冷却逐级加长,cooldownMs=" + cooldown
                            + ",backoffAttempt=" + attempt);
            return;
        }
        int n = failures.incrementAndGet();
        if (n >= policy.failureThreshold() && state.get() == CircuitState.CLOSED) {
            // 先写冷却参数再切换状态，避免并发 tryAcquire 读到陈旧冷却而提前放行试探
            int attempt = backoffAttempt.get();
            long cooldown = policy.cooldownForAttempt(attempt);
            currentCooldownMs.set(cooldown);
            openedAtMs.set(clock.nowMs());
            if (state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                timeline.append(openedAtMs.get(), CircuitState.CLOSED, CircuitState.OPEN,
                        "连续失败达阈值", "consecutiveFailures=" + n
                                + ",threshold=" + policy.failureThreshold()
                                + ",cooldownMs=" + cooldown + ",backoffAttempt=" + attempt);
            }
        }
    }

    /** 当前存储状态（只读，不做任何时间迁移）。 */
    public CircuitState state() {
        return state.get();
    }

    /**
     * 只读健康快照：不推进状态、不改变后续请求判定、不计入任何统计。
     * 并发/高频/连续调用均安全，同一时刻反复调用结果一致。
     */
    public BackendHealth snapshot() {
        CircuitState s = state.get();
        long opened = openedAtMs.get();
        long cooldown = currentCooldownMs.get();
        long remaining = s == CircuitState.OPEN
                ? Math.max(0L, opened + cooldown - clock.nowMs())
                : 0L;
        return new BackendHealth(backendId, s, failures.get(), backoffAttempt.get(),
                opened, cooldown, remaining, s == CircuitState.HALF_OPEN,
                timeline.snapshot());
    }

    public String backendId() {
        return backendId;
    }

    public int failureCount() {
        return failures.get();
    }

    /** 测试支持：手动复位为关闭状态（在时间线上留痕）。 */
    public void reset() {
        CircuitState from = state.get();
        failures.set(0);
        openedAtMs.set(0L);
        currentCooldownMs.set(0L);
        backoffAttempt.set(0);
        state.set(CircuitState.CLOSED);
        timeline.append(clock.nowMs(), from, CircuitState.CLOSED, "手动复位", "reset()");
    }
}
