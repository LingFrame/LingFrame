package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 审计声明注解
 * 用于强制记录日志，或自定义操作描述
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {
    /**
     * 操作动作描述 (例如 "Export Data")
     */
    String action();

    /**
     * 资源标识 (支持简单字符串)
     */
    String resource() default "";
}