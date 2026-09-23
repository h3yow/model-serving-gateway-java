package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个后端的熔断器（线程安全，内存实现）。
 *
 * <ul>
 *   <li>仅后端侧失败（超时/内部错误）计入失败计数；请求非法不计入；</li>
 *   <li>连续失败达到阈值 -> OPEN，期间 {@link #allowRequest()} 快速失败；</li>
 *   <li>OPEN 超过 openDurationMs 后进入 HALF_OPEN，仅放行一个试探请求；</li>
 *   <li>试探成功 -> CLOSED 恢复承载流量；试探失败 -> 重新 OPEN 并重新计时。</li>
 * </ul>
 */
public class CircuitBreaker {

    private final String backendId;
    private final int failureThreshold;
    private final long openDurationMs;

    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0L);

    public CircuitBreaker(String backendId, int failureThreshold, long openDurationMs) {
        this.backendId = backendId;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMs = openDurationMs;
    }

    /**
     * 当前是否允许请求通过。
     * OPEN 冷却结束时，首个调用原子地抢到 HALF_OPEN 试探权（返回 true），其余仍拒绝。
     */
    public boolean allowRequest() {
        CircuitState s = state.get();
        if (s == CircuitState.CLOSED) {
            return true;
        }
        if (s == CircuitState.HALF_OPEN) {
            return false;
        }
        // OPEN：检查冷却
        if (System.currentTimeMillis() - openedAtMs.get() >= openDurationMs) {
            if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                return true; // 抢到试探权
            }
        }
        return false;
    }

    public void onSuccess() {
        failures.set(0);
        state.set(CircuitState.CLOSED);
    }

    /** 记录一次后端侧失败（超时/内部错误）。 */
    public void onFailure() {
        int n = failures.incrementAndGet();
        if (state.get() == CircuitState.HALF_OPEN || n >= failureThreshold) {
            openedAtMs.set(System.currentTimeMillis());
            state.set(CircuitState.OPEN);
        }
    }

    public CircuitState state() {
        // 顺带驱动 OPEN -> HALF_OPEN 的时间迁移（观测接口用）
        if (state.get() == CircuitState.OPEN
                && System.currentTimeMillis() - openedAtMs.get() >= openDurationMs) {
            state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
        }
        return state.get();
    }

    public String backendId() {
        return backendId;
    }

    public int failureCount() {
        return failures.get();
    }

    /** 测试支持：手动复位为关闭状态。 */
    public void reset() {
        failures.set(0);
        openedAtMs.set(0L);
        state.set(CircuitState.CLOSED);
    }
}
