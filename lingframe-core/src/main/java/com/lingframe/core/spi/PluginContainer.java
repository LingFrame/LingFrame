package com.lingframe.core.spi;

import com.lingframe.api.context.PluginContext;

/**
 * 插件容器 SPI
 * 定义插件运行环境的最小契约
 */
public interface PluginContainer {

    /**
     * 启动容器
     *
     * @param context 插件上下文 (Core 传给插件的令牌)
     */
    void start(PluginContext context);

    /**
     * 停止容器
     */
    void stop();

    /**
     * 容器是否存活
     */
    boolean isActive();

    /**
     * 获取容器内的 Bean
     */
    <T> T getBean(Class<T> type);

    /**
     * 获取容器内的 Bean (按名称)
     *
     * @param beanName Spring Bean 名称
     * @return Bean 实例
     */
    Object getBean(String beanName);

    /**
     * 获取容器内所有 Bean 的名称
     */
    String[] getBeanNames();

    /**
     * 获取插件专用的类加载器 (用于 TCCL 劫持)
     */
    ClassLoader getClassLoader();
}