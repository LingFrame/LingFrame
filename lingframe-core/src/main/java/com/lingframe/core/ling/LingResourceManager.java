package com.lingframe.core.ling;

/**
 * LingResourceManager 在组件卸载、更新等生命周期阶段负责集中处理资源清理。
 * <p>
 * 凡有外溢的、带强引用的缓存（诸如 EL/Jackson/Spring 等）都由其协调清理。
 */
public interface LingResourceManager {

    /**
     * 清理所有与目标 lingId 强关联的堆对象和类缓存。
     * 
     * @param lingId      要卸载或更新的组件唯一标识
     * @param classLoader 被卸载组件的类加载器（用于比较或精准释放）
     */
    void cleanupCaches(String lingId, ClassLoader classLoader);

    /**
     * 关闭资源链接，释放物理与网络句柄（诸如数据库连接池、定时任务线程等）。
     * <p>
     * lingId 级兜底：整 Ling 卸载时关闭所有剩余版本的孤儿资源（正常流程应为空表）。
     * 只能由 {@link LingUnloadCoordinator#onLingUnload} 触发。
     */
    void closeResources(String lingId);

    /**
     * 版本级关闭：释放指定版本注册的孤儿资源。
     * <p>
     * 由 {@link LingUnloadCoordinator#onVersionUnload} 路径调用，
     * 解决多版本滚动更新时孤儿资源随旧版本卸载即时释放、不累积的问题。
     */
    void closeResources(String lingId, String version);

    /**
     * 注册一个灵元托管的 {@link AutoCloseable} 孤儿资源；按 {@code (lingId, version)} 隔离。
     * <p>
     * 面向 Spring 容器管不到的孤儿资源（如 {@code onStart} 中手动 {@code new} 出的
     * {@code HikariDataSource}、{@code OkHttpClient}）以及 ling-native 路径的全部资源。
     * 灵元侧唯一入口是 {@code LingContext#registerCloseable}，禁止业务对象直接调用。
     *
     * @param lingId    灵元标识
     * @param version   灵元版本
     * @param closeable 需在卸载时关闭的孤儿子资源
     */
    void registerCloseable(String lingId, String version, AutoCloseable closeable);

    /**
     * 提前反注册（如作者在 {@code onStop} 中已手动关闭，避免重复关闭）。
     * 灵元侧唯一入口是 {@code LingContext#unregisterCloseable}。
     */
    void unregisterCloseable(String lingId, String version, AutoCloseable closeable);
}
