package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 灵珑服务引用
 * 用于注入跨灵元提供的服务接口。
 * * 示例：
 * 
 * @LingReference
 *                private UserService userService;
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface LingReference {

    /**
     * 指定灵元ID (可选)
     * 如果不指定，框架会在所有已安装的灵元中查找实现了该接口的 Bean。
     */
    String lingId() default "";

    /**
     * 超时时间 (毫秒)
     */
    long timeout() default 3000;

    /**
     * 本地兜底逻辑类 (Fallback)
     * 当跨灵元调用发生熔断、超时、找不到服务等情况时，将请求路由至该实现类。
     * 必须是当前应用环境(Spring Context)中的 Bean，或者具有无参构造函数的类。
     */
    Class<?> fallback() default void.class;
}