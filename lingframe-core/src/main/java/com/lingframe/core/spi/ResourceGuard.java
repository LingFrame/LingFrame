package com.lingframe.core.spi;

/**
 * 资源清理守卫 SPI
 * <p>
 * 负责在灵元卸载时清理资源，防止内存泄漏。
 * 默认实现提供 JDBC 驱动反注册和泄漏检测告警，
 * 可通过 SPI 机制注入增强实现以扩展清理能力。
 */
public interface ResourceGuard {

    /**
     * 灵元卸载时清理资源
     * <p>
     * 在 Spring Context 关闭之后、ClassLoader 释放之前调用。
     * 实现类可按需扩展清理逻辑。
     * </p>
     *
     * @param lingId      灵元 ID
     * @param classLoader 灵元的 ClassLoader
     */
    void cleanup(String lingId, ClassLoader classLoader);

    /**
     * 关闭资源守卫
     * <p>
     * 在框架关闭时调用，用于清理后台线程等资源
     * </p>
     */
    default void shutdown() {
    }
}
