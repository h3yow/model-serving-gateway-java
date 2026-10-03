package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

/**
 * 时间源：熔断器的时间推进只依赖此抽象。
 * 生产使用系统墙钟；测试可注入手动推进的时间源，以确定性地验证冷却与退避。
 */
public interface TimeSource {

    /** 当前时间（毫秒）。 */
    long nowMs();

    /** 系统墙钟时间源。 */
    TimeSource SYSTEM = System::currentTimeMillis;
}
