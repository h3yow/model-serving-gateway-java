package com.github.h3yow.modelservinggatewayjava.backend;

import com.github.h3yow.modelservinggatewayjava.model.ScoreRequest;

/**
 * 推理后端抽象（内存实现用于本地模拟）。
 */
public interface InferenceBackend {

    String id();

    double score(ScoreRequest request);
}
