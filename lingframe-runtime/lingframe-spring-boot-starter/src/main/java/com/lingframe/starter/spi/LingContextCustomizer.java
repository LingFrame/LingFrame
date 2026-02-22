package com.lingframe.starter.spi;

import com.lingframe.api.context.LingContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * SPI: 针对 Spring 容器环境的定制器。
 * <p>
 * 这属于 Runtime/Spring 层的生态扩展，并不影响纯粹的 Java 微内核。
 * 允许外部全家桶组件在每个子容器 refresh 前，织入特有的通用 Bean 或环境变量。
 */
public interface LingContextCustomizer {

    /**
     * 定制目标单元的 Spring 容器环境
     *
     * @param context            当前隔离上下文身份
     * @param applicationContext 尚未刷新且刚构建完成的 ConfigurableApplicationContext
     */
    void customize(LingContext context, ConfigurableApplicationContext applicationContext);
}
