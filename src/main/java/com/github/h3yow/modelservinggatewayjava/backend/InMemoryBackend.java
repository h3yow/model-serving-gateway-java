package com.github.h3yow.modelservinggatewayjava.backend;

import com.github.h3yow.modelservinggatewayjava.exception.BackendException;
import com.github.h3yow.modelservinggatewayjava.model.RejectReason;
import com.github.h3yow.modelservinggatewayjava.model.ScoreRequest;

import java.util.Locale;

/**
 * 内存推理后端。
 *
 * <p>特性：</p>
 * <ul>
 *   <li>打分完全确定：仅依赖 (backendId, modelVersion, featureHash)，
 *       相同输入永远得到相同分数，便于并发一致性验证；</li>
 *   <li>可配置固定延迟 latencyMs（用于触发网关侧超时）；</li>
 *   <li>可注入失败类型：none / illegal / timeout / internal，
 *       分别映射到可区分的对外原因码。</li>
 * </ul>
 */
public class InMemoryBackend implements InferenceBackend {

    public static final String FAIL_NONE = "none";
    public static final String FAIL_ILLEGAL = "illegal";
    public static final String FAIL_TIMEOUT = "timeout";
    public static final String FAIL_INTERNAL = "internal";

    private final String id;
    private final long latencyMs;
    private volatile String injectedFailure;

    public InMemoryBackend(String id, long latencyMs, String injectedFailure) {
        this.id = id;
        this.latencyMs = latencyMs;
        this.injectedFailure = injectedFailure == null ? FAIL_NONE : injectedFailure;
    }

    @Override
    public String id() {
        return id;
    }

    /** 动态调整故障注入，用于测试恢复后重新承载流量。 */
    public void setInjectedFailure(String failure) {
        this.injectedFailure = failure == null ? FAIL_NONE : failure;
    }

    @Override
    public double score(ScoreRequest request) {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BackendException(RejectReason.BACKEND_TIMEOUT,
                        "backend " + id + " interrupted while scoring");
            }
        }
        switch (injectedFailure.toLowerCase(Locale.ROOT)) {
            case FAIL_ILLEGAL -> throw new BackendException(RejectReason.BACKEND_ILLEGAL_ARGUMENT,
                    "backend " + id + " rejected feature payload");
            case FAIL_TIMEOUT -> throw new BackendException(RejectReason.BACKEND_TIMEOUT,
                    "backend " + id + " timed out (injected)");
            case FAIL_INTERNAL -> throw new BackendException(RejectReason.BACKEND_INTERNAL_ERROR,
                    "backend " + id + " internal error (injected)");
            default -> {
                return deterministicScore(request);
            }
        }
    }

    /**
     * 基于 FNV-1a 64 位哈希的确定性伪随机分数，范围 [0, 1)。
     */
    private double deterministicScore(ScoreRequest request) {
        String raw = id + "|" + safe(request.modelVersion()) + "|" + safe(request.featureHash());
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < raw.length(); i++) {
            h ^= raw.charAt(i);
            h *= 0x100000001b3L;
        }
        double v = (double) Math.abs(h % 1_000_000L) / 1_000_000.0d;
        return Math.round(v * 10000.0d) / 10000.0d;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
