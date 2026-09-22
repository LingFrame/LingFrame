package com.lingframe.core.spi;

/**
 * 灵元卸载清理钩子 SPI
 * <p>
 * 在灵元卸载时执行清理动作，防止 ClassLoader 与线程残留泄漏。
 * 该接口是与生态无关的内核契约：实现者只感知 lingId 与 ClassLoader，
 * 不感知任何容器或生态语义。
 * <p>
 * 卸载编排由 {@code LingUnloadCoordinator} 负责，分为两阶段：
 * <ul>
 *   <li>生态阶段：清理外部生态框架的静态缓存（仅注册了生态 Hook 的场景执行）</li>
 *   <li>JVM 阶段：清理 JDBC 驱动、线程引用、ShutdownHook 等 JVM 级残留（永远执行）</li>
 * </ul>
 * 同阶段内并行执行、阶段间串行执行。Hook 自身不声明阶段，
 * 阶段归属由装配点决定（生态 Hook 注册到生态桶、JVM Hook 注册到 JVM 桶）。
 */
public interface LingUnloadHook {

    /**
     * 灵元卸载时执行清理。
     *
     * @param lingId      灵元 ID
     * @param classLoader 灵元的 ClassLoader
     */
    void cleanup(String lingId, ClassLoader classLoader);

    /**
     * 关闭清理钩子自身持有的后台资源。
     * <p>
     * 在框架整体关闭时调用，用于释放后台线程等长期持有资源。
     */
    default void shutdown() {
    }
}
