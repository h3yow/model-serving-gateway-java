package com.github.h3yow.modelservinggatewayjava.gateway.backend;

/**
 * 推理后端类型（内置内存实现模拟多种后端）。
 */
public enum BackendType {
    /** CPU 推理后端。 */
    CPU,
    /** GPU 推理后端。 */
    GPU,
    /** 边缘轻量后端。 */
    EDGE
}
