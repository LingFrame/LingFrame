package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 权限声明注解
 * 用于覆盖默认的智能推导规则，显式指定方法所需的权限。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /**
     * 权限标识符 (例如 "user:export")
     */
    String value();

    /**
     * 描述信息
     */
    String description() default "";
}