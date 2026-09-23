package com.github.h3yow.modelservinggatewayjava.gateway.routing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 配置纪元：版本升级或灰度权重调整时递增，作为缓存失效与流量观测的依据。
 * 纪元变化时同步通知监听者（如评分缓存），实现即时失效。
 */
public class ConfigEpoch {

    private final AtomicLong epoch = new AtomicLong(0);
    private final List<Consumer<Long>> listeners = new CopyOnWriteArrayList<>();

    public void onChange(Consumer<Long> listener) {
        listeners.add(listener);
    }

    /** 配置变更：纪元 +1 并通知监听者，返回新值。 */
    public long bump() {
        long v = epoch.incrementAndGet();
        for (Consumer<Long> l : listeners) {
            l.accept(v);
        }
        return v;
    }

    public long current() {
        return epoch.get();
    }
}
