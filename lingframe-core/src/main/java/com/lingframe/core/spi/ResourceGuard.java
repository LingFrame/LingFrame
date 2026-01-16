package com.lingframe.core.spi;

/**
 * 资源清理守卫 SPI
 * <p>
 * 负责在插件卸载时清理资源，防止内存泄漏。
 * 默认实现提供 JDBC 驱动反注册和泄漏检测告警，
 * 可通过 SPI 机制注入增强实现以扩展清理能力。
 */
public interface ResourceGuard {

    /**
     * 插件卸载时清理资源
     * <p>
     * 在 Spring Context 关闭之后、ClassLoader 释放之前调用。
     * 实现类可按需扩展清理逻辑。
     * </p>
     *
     * @param pluginId    插件 ID
     * @param classLoader 插件的 ClassLoader
     */
    void cleanup(String pluginId, ClassLoader classLoader);

    /**
     * 检测 ClassLoader 是否存在泄漏
     * <p>
     * 延迟检测 ClassLoader 是否被 GC 回收，未回收则输出告警日志。
     * 此方法应在 cleanup() 之后调用。
     * </p>
     *
     * @param pluginId    插件 ID
     * @param classLoader 插件的 ClassLoader（将被包装为 WeakReference）
     */
    void detectLeak(String pluginId, ClassLoader classLoader);
}
