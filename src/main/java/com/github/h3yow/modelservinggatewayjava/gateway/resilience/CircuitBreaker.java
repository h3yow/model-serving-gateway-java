package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 单个后端的熔断器 / 自愈控制器（线程安全，内存实现）。
 *
 * <p>状态机：
 * <pre>
 *   CLOSED（正常承载） --连续失败达阈值--> OPEN（隔离，快速失败，流量转健康后端）
 *   OPEN --冷却结束且有真实流量到达--> HALF_OPEN（仅放行唯一一个试探请求）
 *   HALF_OPEN --试探成功--> CLOSED（重新正常承载流量，冷却复位为初始值）
 *   HALF_OPEN --试探失败--> OPEN（再次隔离，冷却时间逐级退避，直到上限）
 * </pre>
 *
 * <p>关键保证：
 * <ul>
 *   <li><b>自动恢复</b>：OPEN 冷却结束后，第一个真实请求原子抢到唯一试探权，
 *       无需人工干预，也不会出现冷却结束却永远停在 OPEN 的情况；</li>
 *   <li><b>同一时刻最多一个试探</b>：HALF_OPEN 期间其它真实请求一律被拒绝（降级到健康后端），
 *       避免反复试探打垮虚弱后端；</li>
 *   <li><b>退避可配置</b>：试探失败后的冷却 = min(当前冷却 * multiplier, maxBackoff)；</li>
 *   <li><b>观测无副作用</b>：{@link #state()} / {@link #view()} 只读，
 *       高频/并发查询不会推进或打断恢复，也不触碰任何统计；</li>
 *   <li><b>可追溯</b>：每次关键状态变化追加到有界 {@link StateTimeline}。</li>
 * </ul>
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** 变化原因常量，同时写入时间线与日志。 */
    public static final String REASON_FAILURE_THRESHOLD_REACHED = "FAILURE_THRESHOLD_REACHED";
    public static final String REASON_RECOVERY_PROBE_GRANTED = "RECOVERY_PROBE_GRANTED";
    public static final String REASON_RECOVERY_PROBE_SUCCEEDED = "RECOVERY_PROBE_SUCCEEDED";
    public static final String REASON_RECOVERY_PROBE_FAILED = "RECOVERY_PROBE_FAILED";
    public static final String REASON_MANUAL_RESET = "MANUAL_RESET";

    public static final String PHASE_NORMAL = "NORMAL";
    public static final String PHASE_ISOLATED = "ISOLATED";
    /** 隔离冷却已结束、等待下一个真实请求触发试探。 */
    public static final String PHASE_ISOLATED_COOLDOWN_DUE = "ISOLATED_COOLDOWN_DUE";
    public static final String PHASE_PROBING = "PROBING";

    private final String backendId;
    private final CircuitBreakerConfig config;
    private final StateTimeline timeline;

    private CircuitState state = CircuitState.CLOSED;
    private int failures = 0;
    private int probeAttempt = 0;
    private long openedAtMs = 0L;
    private long currentBackoffMs;

    public CircuitBreaker(String backendId, int failureThreshold, long openDurationMs) {
        this(backendId, new CircuitBreakerConfig(failureThreshold, openDurationMs,
                1.0d, openDurationMs, 20));
    }

    public CircuitBreaker(String backendId, CircuitBreakerConfig config) {
        this.backendId = backendId;
        this.config = config;
        this.currentBackoffMs = config.initialBackoffMs();
        this.timeline = new StateTimeline(config.timelineCapacity());
    }

    /**
     * 真实流量的准入判定（会驱动 OPEN -&gt; HALF_OPEN 的迁移，仅限真实请求调用）。
     * 冷却结束时首个调用原子拿到试探权；同一时刻其它调用全部被拒绝。
     */
    public synchronized AdmissionResult admit() {
        long now = System.currentTimeMillis();
        if (state == CircuitState.CLOSED) {
            return AdmissionResult.passNormal();
        }
        if (state == CircuitState.HALF_OPEN) {
            // 已有一个试探请求在执行：其余流量继续走健康后端，绝不并发试探
            return AdmissionResult.reject(AdmissionResult.SKIP_PROBE_IN_FLIGHT);
        }
        // OPEN：冷却结束则原子进入 HALF_OPEN 并把唯一试探权交给本请求
        if (now - openedAtMs >= currentBackoffMs) {
            CircuitState from = state;
            state = CircuitState.HALF_OPEN;
            probeAttempt++;
            StateTransitionEvent e = new StateTransitionEvent(now, from, CircuitState.HALF_OPEN,
                    REASON_RECOVERY_PROBE_GRANTED, failures, currentBackoffMs, probeAttempt);
            timeline.append(e);
            log.info("[状态迁移] backend={} {}->{} reason={} attempt={} backoff={}ms",
                    backendId, e.from(), e.to(), e.reason(), e.probeAttempt(), e.backoffMs());
            return AdmissionResult.passProbe();
        }
        return AdmissionResult.reject(AdmissionResult.SKIP_ISOLATED);
    }

    /**
     * 兼容旧调用：等价于 {@code admit().allowed()}，仅用于真实流量路径。
     */
    public boolean allowRequest() {
        return admit().allowed();
    }

    /**
     * 后端调用成功。
     * 试探成功：HALF_OPEN -&gt; CLOSED，后端重新正常承载流量，退避计数复位。
     */
    public synchronized void onSuccess() {
        long now = System.currentTimeMillis();
        if (state == CircuitState.HALF_OPEN) {
            StateTransitionEvent e = new StateTransitionEvent(now, CircuitState.HALF_OPEN, CircuitState.CLOSED,
                    REASON_RECOVERY_PROBE_SUCCEEDED, 0, currentBackoffMs, probeAttempt);
            timeline.append(e);
            log.info("[状态迁移] backend={} HALF_OPEN->CLOSED reason={} attempt={}",
                    backendId, e.reason(), e.probeAttempt());
        }
        failures = 0;
        probeAttempt = 0;
        currentBackoffMs = config.initialBackoffMs();
        openedAtMs = 0L;
        state = CircuitState.CLOSED;
    }

    /**
     * 记录一次后端侧失败（超时 / 内部错误；非法请求由上层保证不调用这里）。
     * CLOSED 下连续失败达阈值 -&gt; OPEN；HALF_OPEN 试探失败 -&gt; OPEN 且冷却逐级退避。
     */
    public synchronized void onFailure() {
        long now = System.currentTimeMillis();
        failures++;
        if (state == CircuitState.HALF_OPEN) {
            CircuitState from = state;
            long newBackoff = nextBackoff();
            currentBackoffMs = newBackoff;
            openedAtMs = now;
            state = CircuitState.OPEN;
            StateTransitionEvent e = new StateTransitionEvent(now, from, CircuitState.OPEN,
                    REASON_RECOVERY_PROBE_FAILED, failures, newBackoff, probeAttempt);
            timeline.append(e);
            log.info("[状态迁移] backend={} HALF_OPEN->OPEN reason={} attempt={} nextBackoff={}ms",
                    backendId, e.reason(), e.probeAttempt(), e.backoffMs());
        } else if (state == CircuitState.CLOSED && failures >= config.failureThreshold()) {
            currentBackoffMs = config.initialBackoffMs();
            openedAtMs = now;
            state = CircuitState.OPEN;
            StateTransitionEvent e = new StateTransitionEvent(now, CircuitState.CLOSED, CircuitState.OPEN,
                    REASON_FAILURE_THRESHOLD_REACHED, failures, currentBackoffMs, probeAttempt);
            timeline.append(e);
            log.info("[状态迁移] backend={} CLOSED->OPEN reason={} failures={} backoff={}ms",
                    backendId, e.reason(), e.failureCount(), e.backoffMs());
        }
    }

    /** 逐级退避：min(当前冷却 * multiplier, maxBackoff)；乘数 <=1 时退避表现为恒定。 */
    private long nextBackoff() {
        long base = currentBackoffMs > 0 ? currentBackoffMs : config.initialBackoffMs();
        long bumped = (long) Math.ceil(base * config.backoffMultiplier());
        return Math.min(bumped, config.maxBackoffMs());
    }

    /**
     * 纯读：当前权威状态。不会把 OPEN 提前迁移到 HALF_OPEN——
     * 状态迁移只能由 {@link #admit()}（真实流量）完成。
     */
    public synchronized CircuitState state() {
        return state;
    }

    /**
     * 纯读：生成后端健康/恢复进度快照（含剩余隔离时长与状态时间线）。
     * 连续查、并发查、高频查均无任何副作用，不推进也不打断恢复。
     */
    public synchronized BackendHealthView view() {
        long now = System.currentTimeMillis();
        long remaining = 0L;
        boolean eligible = false;
        String phase;
        switch (state) {
            case CLOSED -> phase = PHASE_NORMAL;
            case HALF_OPEN -> phase = PHASE_PROBING;
            default -> {
                long elapsed = now - openedAtMs;
                remaining = Math.max(0L, currentBackoffMs - elapsed);
                eligible = remaining == 0L;
                phase = eligible ? PHASE_ISOLATED_COOLDOWN_DUE : PHASE_ISOLATED;
            }
        }
        return new BackendHealthView(backendId, state, phase, failures, probeAttempt,
                currentBackoffMs, remaining, eligible, state == CircuitState.HALF_OPEN,
                timeline.snapshot());
    }

    public synchronized List<StateTransitionEvent> timeline() {
        return timeline.snapshot();
    }

    public String backendId() {
        return backendId;
    }

    public synchronized int failureCount() {
        return failures;
    }

    public synchronized int probeAttempt() {
        return probeAttempt;
    }

    public synchronized long currentBackoffMs() {
        return currentBackoffMs;
    }

    /** 测试/运维支持：复位为初始关闭状态并清空时间线（本身记录一条复位事件）。 */
    public synchronized void reset() {
        timeline.clear();
        failures = 0;
        probeAttempt = 0;
        currentBackoffMs = config.initialBackoffMs();
        openedAtMs = 0L;
        CircuitState from = state;
        state = CircuitState.CLOSED;
        if (from != CircuitState.CLOSED) {
            timeline.append(new StateTransitionEvent(System.currentTimeMillis(), from,
                    CircuitState.CLOSED, REASON_MANUAL_RESET, 0, currentBackoffMs, 0));
        }
    }
}
