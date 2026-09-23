package com.github.h3yow.modelservinggatewayjava.circuit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每后端熔断器。
 *
 * <p>状态机：</p>
 * <ul>
 *   <li>CLOSED：正常放行；连续失败达到 failureThreshold 后转到 OPEN；</li>
 *   <li>OPEN：拒绝所有请求（上层返回 CIRCUIT_OPEN，不再调用后端）；
 *       经过 openStateMs 后允许一次试探请求进入 HALF_OPEN；</li>
 *   <li>HALF_OPEN：试探成功 -> CLOSED（恢复承载流量）；试探失败 -> OPEN（重新计时）。</li>
 * </ul>
 *
 * <p>成功计数在 CLOSED 下会清零连续失败数；熔断只影响“之后”的请求，
 * 已完成的评分结果不会被改写。</p>
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openStateMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedAt = 0L;

    public CircuitBreaker(int failureThreshold, long openStateMs) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be > 0");
        }
        this.failureThreshold = failureThreshold;
        this.openStateMs = openStateMs;
    }

    /**
     * 是否允许本次请求通过。OPEN 超时后仅放一个请求进入 HALF_OPEN。
     */
    public boolean allowRequest() {
        State s = state.get();
        if (s == State.CLOSED) {
            return true;
        }
        if (s == State.HALF_OPEN) {
            // 试探窗口内只允许在途的那一个，其余仍拒绝
            return false;
        }
        // OPEN：检查冷却期是否结束
        if (System.currentTimeMillis() - openedAt >= openStateMs) {
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                return true;
            }
        }
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    public void recordFailure() {
        int f = consecutiveFailures.incrementAndGet();
        State s = state.get();
        if (s == State.HALF_OPEN) {
            // 试探失败：重新打开并重新计时
            reopen();
            return;
        }
        if (f >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            openedAt = System.currentTimeMillis();
        }
    }

    private void reopen() {
        state.set(State.OPEN);
        consecutiveFailures.set(failureThreshold);
        openedAt = System.currentTimeMillis();
    }

    public State state() {
        // 顺带做一次只读的冷却期判定，保证观测到的状态及时反映 OPEN->可恢复
        State s = state.get();
        if (s == State.OPEN && System.currentTimeMillis() - openedAt >= openStateMs) {
            return State.OPEN;
        }
        return s;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
