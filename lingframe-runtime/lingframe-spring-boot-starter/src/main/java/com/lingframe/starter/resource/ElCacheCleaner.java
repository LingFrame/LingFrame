package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

/**
 * 清理 EL（Expression Language）BeanELResolver 缓存。
 * <p>
 * Spring Boot 2.x 使用 javax.el，Spring Boot 3.x 使用 jakarta.el，
 * 通过 purgeBeanClasses(ClassLoader) 公开 API 精确清理。
 */
@Slf4j
final class ElCacheCleaner {

    void clear(String lingId, ClassLoader lingClassLoader) {
        String[] elClassNames = {
                "jakarta.el.BeanELResolver",
                "javax.el.BeanELResolver"
        };
        for (String className : elClassNames) {
            try {
                Class<?> beanELResolverClass = ClassUtils.forName(className, lingClassLoader);
                Method purgeMethod = ReflectionUtils.findMethod(
                        beanELResolverClass, "purgeBeanClasses", ClassLoader.class);
                if (purgeMethod != null) {
                    purgeMethod.setAccessible(true);
                    purgeMethod.invoke(null, lingClassLoader);
                    log.debug("[{}] Cleared EL cache: {}", lingId, className);
                }
            } catch (ClassNotFoundException e) {
                // 正常：Boot 2.x 没有 jakarta，Boot 3.x 没有 javax
            } catch (Exception e) {
                log.debug("[{}] EL cache cleanup failed for {}: {}", lingId, className, e.getMessage());
            }
        }
    }
}
