package com.github.h3yow.modelservinggatewayjava.cache;

import com.github.h3yow.modelservinggatewayjava.model.ScoreResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 评分缓存。
 *
 * <p>保证：</p>
 * <ul>
 *   <li>TTL：过期条目不命中；</li>
 *   <li>配置版本失效：模型版本升级或权重调整后 registry 的 configVersion 递增，
 *       旧配置版本写入的条目一律视为失效，不会读到旧版本结果；</li>
 *   <li>single-flight：有效期内相同键的并发请求只允许一个线程真正计算，
 *       其余共享同一个 Future，杜绝重复计算与结果串号；</li>
 *   <li>只缓存成功结果：计算异常时移除占位 Future，其他等待者收到同一异常，
 *       且失败不污染缓存。</li>
 * </ul>
 */
public class ScoringCache {

    /** 一次查询的结果：共享的 Future + 是否命中已有条目。 */
    public record Lookup(CompletableFuture<ScoreResponse> future, boolean hit) {
    }

    private static class Entry {
        final CompletableFuture<ScoreResponse> future;
        final long configVersion;
        final long createdAt;

        Entry(CompletableFuture<ScoreResponse> future, long configVersion, long createdAt) {
            this.future = future;
            this.configVersion = configVersion;
            this.createdAt = createdAt;
        }
    }

    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new Object();
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final AtomicInteger hitCount = new AtomicInteger();

    public ScoringCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public Lookup getOrLoad(String key, long configVersion,
                            Supplier<ScoreResponse> loader) {
        long now = System.currentTimeMillis();

        Entry existing = store.get(key);
        if (valid(existing, configVersion, now)) {
            hitCount.incrementAndGet();
            return new Lookup(existing.future, true);
        }

        // 按 key 分段加锁：只有一个线程负责加载，其余线程在锁内拿到同一占位 Future
        synchronized (stripedLock(key)) {
            Entry current = store.get(key);
            if (valid(current, configVersion, System.currentTimeMillis())) {
                hitCount.incrementAndGet();
                return new Lookup(current.future, true);
            }

            CompletableFuture<ScoreResponse> promise = new CompletableFuture<>();
            store.put(key, new Entry(promise, configVersion, System.currentTimeMillis()));

            try {
                ScoreResponse result = loader.get();
                promise.complete(result);
            } catch (Throwable t) {
                store.remove(key);
                promise.completeExceptionally(t);
            }
            return new Lookup(promise, false);
        }
    }

    private boolean valid(Entry e, long configVersion, long now) {
        return e != null
                && e.configVersion == configVersion
                && now - e.createdAt < ttlMs;
    }

    /** 固定数量的锁分段，避免不同 key 互相阻塞。 */
    private Object stripedLock(String key) {
        return LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)];
    }

    /** 手动清空（配置变更时也可直接调用；条目自带 configVersion 守卫，非必须）。 */
    public void invalidate() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    public int hitCount() {
        return hitCount.get();
    }
}
