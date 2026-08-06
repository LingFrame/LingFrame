package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * 清理 Spring Objenesis 实例化器缓存。
 * <p>
 * 三套策略：ObjenesisCglibAopProxy.objenesis -> SpringObjenesis 实例 -> BaseInstantiatorStrategy。
 * 缓存 key 通常是 ClassLoader 或灵元加载的 Class。
 */
@Slf4j
final class ObjenesisCacheCleaner {

    void clear(String lingId, ClassLoader lingClassLoader) {
        // 策略1：通过 ObjenesisCglibAopProxy.objenesis -> cache
        boolean cleared = clearObjenesisViaProxy(lingId, lingClassLoader);
        // 策略2：直接找 SpringObjenesis 实例中的缓存
        if (!cleared) {
            clearObjenesisDirect(lingId, lingClassLoader);
        }
        // 策略3：BaseInstantiatorStrategy 缓存
        clearBaseInstantiatorCache(lingId, lingClassLoader);
    }

    private boolean clearObjenesisViaProxy(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> proxyClass = Class.forName("org.springframework.aop.framework.ObjenesisCglibAopProxy");
            Field objenesisField = SpringCleanupSupport.findStaticFieldByType(
                    proxyClass, "org.springframework.objenesis.SpringObjenesis");
            if (objenesisField == null) {
                try {
                    objenesisField = proxyClass.getDeclaredField("objenesis");
                } catch (NoSuchFieldException e) {
                    return false;
                }
            }
            objenesisField.setAccessible(true);
            Object objenesis = objenesisField.get(null);
            if (objenesis == null)
                return false;
            return SpringCleanupSupport.clearMapFieldsByClassLoaderKey(objenesis, lingClassLoader,
                    "[" + lingId + "] Objenesis");
        } catch (ClassNotFoundException e) {
            log.debug("[{}] ObjenesisCglibAopProxy not found (may be removed in this Spring version)", lingId);
            return false;
        } catch (Exception e) {
            log.debug("[{}] Objenesis via proxy cleanup failed: {}", lingId, e.getMessage());
            return false;
        }
    }

    private void clearObjenesisDirect(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName("org.springframework.objenesis.SpringObjenesis");
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(f.getType()))
                    continue;
                try {
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(null);
                    if (map != null) {
                        SpringCleanupSupport.removeByClassLoaderKey(map, lingClassLoader);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (ClassNotFoundException e) {
            log.trace("[{}] SpringObjenesis not on classpath", lingId);
        } catch (Exception e) {
            log.debug("[{}] Objenesis direct cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private void clearBaseInstantiatorCache(String lingId, ClassLoader lingClassLoader) {
        String[] classNames = {
                "org.springframework.objenesis.strategy.BaseInstantiatorStrategy",
                "org.springframework.objenesis.strategy.StdInstantiatorStrategy"
        };
        String[] fieldNames = { "INSTANTIATOR_CACHE", "cache", "CACHE" };
        for (String className : classNames) {
            try {
                Class<?> c = Class.forName(className);
                for (String fieldName : fieldNames) {
                    try {
                        Field f = c.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object cache = f.get(null);
                        if (cache instanceof Map<?, ?>) {
                            SpringCleanupSupport.removeByClassLoaderKey((Map<?, ?>) cache, lingClassLoader);
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                log.trace("[{}] BaseInstantiator cleanup for {} failed", lingId, className);
            }
        }
    }
}
