package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 灵珑服务引用
 * 用于注入跨模块提供的服务接口。
 * * 示例：
 * @LingReference
 * private UserService userService;
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface LingReference {

    /**
     * 指定插件ID (可选)
     * 如果不指定，框架会在所有已安装的插件中查找实现了该接口的 Bean。
     */
    String pluginId() default "";

    /**
     * 超时时间 (毫秒)
     */
    long timeout() default 3000;
}