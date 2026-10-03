package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 自愈退避策略（可配置）。
 *
 * @param failureThreshold    连续失败达到此次数后隔离（OPEN）
 * @param initialCooldownMs   首次隔离冷却时长（毫秒）
 * @param backoffMultiplier   每次试探失败后下一次冷却时长的乘数（&gt;=1）
 * @param maxCooldownMs       冷却时长上限（毫秒）
 */
public record RecoveryPolicy(int failureThreshold, long initialCooldownMs,
                             double backoffMultiplier, long maxCooldownMs) {

    public RecoveryPolicy {
        failureThreshold = Math.max(1, failureThreshold);
        if (initialCooldownMs < 0) {
            initialCooldownMs = 0;
        }
        if (!(backoffMultiplier >= 1d)) {
            backoffMultiplier = 1d;
        }
        if (maxCooldownMs < initialCooldownMs) {
            maxCooldownMs = initialCooldownMs;
        }
    }

    /**
     * 按退避轮次计算冷却时长，逐级加长并封顶。
     *
     * @param attempt 第几次隔离（0 表示首次隔离），首次冷却 = initialCooldownMs
     */
    public long cooldownForAttempt(int attempt) {
        long cooldown = initialCooldownMs;
        for (int i = 0; i < attempt; i++) {
            double next = cooldown * backoffMultiplier;
            // 溢出保护：乘法超过 long 上限或超过配置上限时，直接取上限
            if (next >= maxCooldownMs || next <= 0d || next > Long.MAX_VALUE) {
                return maxCooldownMs;
            }
            cooldown = (long) next;
            if (cooldown >= maxCooldownMs) {
                return maxCooldownMs;
            }
        }
        return Math.min(cooldown, maxCooldownMs);
    }
}
