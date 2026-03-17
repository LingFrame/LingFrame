package com.lingframe.core.spi;

/**
 * 类加载器泄露检测器接口
 * <p>
 * 职责：负责检测卸载后的 ClassLoader 是否能被正常回收。
 */
public interface LeakDetector {

    /**
     * 启动对 ClassLoader 的泄露检测
     *
     * @param lingId      灵元 ID
     * @param classLoader 待检测的 ClassLoader
     */
    void detectLeak(String lingId, ClassLoader classLoader);

    /**
     * 关闭检测器，释放后台资源
     */
    default void shutdown() {}
}
