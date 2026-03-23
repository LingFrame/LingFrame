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
     * 关闭资源链接，释放物理与网络句柄（诸如数据库连接池、定时任务线程等）
     */
    void closeResources(String lingId);
}
