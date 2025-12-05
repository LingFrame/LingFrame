package com.lingframe.api.annotation;

import java.lang.annotation.*;

/**
 * 扩展实现标记注解
 * <p>
 * 用于标记 {@link com.lingframe.api.extension.ExtensionPoint} 的具体实现类。
 * 被此注解标记的类将被注册为扩展Bean。
 * </p>
 * 
 * @author LingFrame
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Extension {
    
    /**
     * 扩展实现的优先级/排序序号
     * 数值越小优先级越高
     * @return 序号
     */
    int ordinal() default 0;
    
    /**
     * 扩展实现的描述信息
     * @return 描述
     */
    String description() default "";
}
