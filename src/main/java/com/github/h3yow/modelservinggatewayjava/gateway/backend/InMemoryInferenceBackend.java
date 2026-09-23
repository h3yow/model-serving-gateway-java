package com.github.h3yow.modelservinggatewayjava.gateway.backend;

import com.github.h3yow.modelservinggatewayjava.gateway.model.GatewayErrorCode;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存推理后端：用确定性公式模拟模型评分，并可在运行时注入失败模式，
 * 以稳定复现超时 / 内部错误 / 非法请求三类异常。
 *
 * <p>评分为 {@code (版本, 特征)} 的确定性函数，与具体后端无关，
 * 因此同版本不同后端对同一输入给出完全一致的分数（灰度/降级下结果一致）。
 */
public class InMemoryInferenceBackend implements InferenceBackend {

    private final String id;
    private final BackendType type;
    private final String version;
    private final long latencyMs;

    private final AtomicReference<FailureMode> failureMode =
            new AtomicReference<>(FailureMode.NONE);

    public InMemoryInferenceBackend(String id, BackendType type, String version) {
        this(id, type, version, 0L);
    }

    public InMemoryInferenceBackend(String id, BackendType type, String version, long latencyMs) {
        this.id = id;
        this.type = type;
        this.version = version;
        this.latencyMs = latencyMs;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BackendType type() {
        return type;
    }

    @Override
    public String version() {
        return version;
    }

    /** 注入失败模式（NONE/TIMEOUT/INTERNAL_ERROR/INVALID_REQUEST）。 */
    public void setFailureMode(FailureMode mode) {
        failureMode.set(mode == null ? FailureMode.NONE : mode);
    }

    public FailureMode failureMode() {
        return failureMode.get();
    }

    @Override
    public double score(ScoreRequest request) {
        FailureMode mode = failureMode.get();
        switch (mode) {
            case TIMEOUT -> throw new BackendException(GatewayErrorCode.BACKEND_TIMEOUT,
                    "backend '" + id + "' timed out after configured timeout");
            case INTERNAL_ERROR -> throw new BackendException(GatewayErrorCode.BACKEND_INTERNAL_ERROR,
                    "backend '" + id + "' internal inference error");
            case INVALID_REQUEST -> throw new BackendException(GatewayErrorCode.INVALID_REQUEST,
                    "backend '" + id + "' rejected request: feature tensor malformed");
            default -> {
                // 继续正常处理
            }
        }

        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BackendException(GatewayErrorCode.BACKEND_TIMEOUT,
                        "backend '" + id + "' interrupted while simulating latency");
            }
        }

        return deterministicScore(version, request.features());
    }

    /**
     * 确定性评分：仅依赖版本与特征，与后端实例无关。
     * 采用稳定哈希构造 sigmoid，保证灰度切换/降级时同输入同版本结果一致。
     */
    static double deterministicScore(String version, java.util.List<Double> features) {
        double sum = 0d;
        for (int i = 0; i < features.size(); i++) {
            double x = features.get(i);
            sum += x * (1d + 0.01d * (i + 1));
        }
        long h = stableHash(version + "#" + features);
        double versionBias = (h % 1000) / 10000.0;
        double z = 0.4d * sum + versionBias;
        double p = 1.0d / (1.0d + Math.exp(-z));
        return Math.round(p * 10000d) / 10000d;
    }

    private static long stableHash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }
}
