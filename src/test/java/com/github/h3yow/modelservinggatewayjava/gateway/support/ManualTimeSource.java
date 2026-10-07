package com.github.h3yow.modelservinggatewayjava.gateway.support;

import com.github.h3yow.modelservinggatewayjava.gateway.resilience.TimeSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用时间源：时间只在显式 {@link #advanceMs(long)} 时前进，
 * 用于确定性地验证冷却到期、逐级退避，不依赖真实 sleep。
 */
public class ManualTimeSource implements TimeSource {

    private final AtomicLong now = new AtomicLong(0L);

    @Override
    public long nowMs() {
        return now.get();
    }

    public void advanceMs(long deltaMs) {
        now.addAndGet(Math.max(0L, deltaMs));
    }
}
