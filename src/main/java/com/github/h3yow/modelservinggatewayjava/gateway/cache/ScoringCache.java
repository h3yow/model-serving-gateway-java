package com.github.h3yow.modelservinggatewayjava.gateway.cache;

import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 评分缓存。
 *
 * <ul>
 *   <li>有效期内相同 (版本, 特征) 请求直接复用，不重复计算；</li>
 *   <li>配置纪元变化（版本升级/权重调整）时整体失效；</li>
 *   <li>并发相同请求通过键级去重（每键一把锁 + in-flight future）保证只计算一次，
 *       每个请求拿到属于自己 requestId 的结果，不串号；</li>
 *   <li>仅成功结果可缓存；失败结果立即移除，不污染后续请求。</li>
 * </ul>
 */
public class ScoringCache {

    private record Entry(ScoreResult result, long createdAtMs, long epoch) {
    }

    private final long ttlMs;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    /** 同一 key 的并发计算在此汇合，保证 loader 只执行一次。 */
    private final Map<String, CompletableFuture<ScoreResult>> inFlight = new ConcurrentHashMap<>();
    /** 每键一把锁，避免双重检查竞态。 */
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    private volatile long epoch = 0L;
    private volatile long invalidations = 0L;

    public ScoringCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * 订阅配置纪元变化：纪元一旦推进（版本升级/权重调整）立即清空缓存，
     * 不必等待下一次请求惰性清理。
     */
    public void bindEpoch(ConfigEpochView epochView) {
        epochView.onChange(newEpoch -> {
            synchronized (ScoringCache.this) {
                store.clear();
                this.epoch = newEpoch;
                this.invalidations++;
            }
        });
    }

    /** 最小的纪元视图，避免 cache 包反向依赖 routing 包。 */
    public interface ConfigEpochView {
        void onChange(Consumer<Long> listener);
    }

    /**
     * @param version     命中版本
     * @param requestId   当前请求 id（用于给缓存结果生成属于该请求的副本）
     * @param featuresKey 请求特征的规范化键
     * @param newEpoch    当前配置纪元
     * @param loader      未命中时的计算逻辑
     */
    public ScoreResult computeIfAbsent(String version, String requestId, String featuresKey,
                                       long newEpoch, Supplier<ScoreResult> loader) {
        String key = cacheKey(version, featuresKey);

        // 防御性对齐：正常情况下纪元推进已由监听器即时清空；此处仅兜底。
        if (newEpoch != this.epoch) {
            synchronized (this) {
                if (newEpoch != this.epoch) {
                    store.clear();
                    this.epoch = newEpoch;
                }
            }
        }

        Entry hit = store.get(key);
        if (hit != null && System.currentTimeMillis() - hit.createdAtMs() <= ttlMs) {
            return rebind(hit.result(), requestId, true, "CACHE_HIT");
        }

        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        CompletableFuture<ScoreResult> future;
        boolean owner = false;
        synchronized (lock) {
            // 双检：可能在等待锁期间已被其它线程算出并缓存
            hit = store.get(key);
            if (hit != null && System.currentTimeMillis() - hit.createdAtMs() <= ttlMs) {
                return rebind(hit.result(), requestId, true, "CACHE_HIT");
            }
            future = inFlight.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                inFlight.put(key, future);
                owner = true;
            }
        }

        if (owner) {
            ScoreResult computed;
            try {
                computed = loader.get();
                future.complete(computed);
                if (computed.success()) {
                    store.put(key, new Entry(computed, System.currentTimeMillis(), newEpoch));
                }
            } catch (RuntimeException ex) {
                future.completeExceptionally(ex);
                throw ex;
            } finally {
                synchronized (lock) {
                    inFlight.remove(key);
                }
            }
            return rebind(computed, requestId, false, computed.decisionReason());
        }

        // 非 owner：等待同一结果，绝不重复计算；标记为并发去重命中
        try {
            ScoreResult shared = future.join();
            return rebind(shared, requestId, true, "CONCURRENT_DEDUP");
        } catch (java.util.concurrent.CompletionException ce) {
            throw (RuntimeException) ce.getCause();
        }
    }

    private static ScoreResult rebind(ScoreResult template, String requestId,
                                      boolean fromCache, String reason) {
        return new ScoreResult(requestId, template.success(), template.result(),
                template.servedVersion(), template.backendId(), fromCache,
                reason, template.errorCode(), template.errorMessage());
    }

    private static String cacheKey(String version, String featuresKey) {
        return version + "|" + featuresKey;
    }

    /** 配置变更导致缓存失效（也可由管理接口手动调用）。 */
    public synchronized void invalidate() {
        store.clear();
        this.epoch = this.epoch; // 纪元由外部传入推进；此处仅清理
        this.invalidations++;
    }

    public long cachedAtEpoch() {
        return epoch;
    }

    public long invalidationCount() {
        return invalidations;
    }

    public int size() {
        return store.size();
    }
}
