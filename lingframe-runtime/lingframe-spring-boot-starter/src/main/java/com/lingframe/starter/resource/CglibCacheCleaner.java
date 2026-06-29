package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * 清理 Spring CGLIB AbstractClassGenerator 静态缓存。
 * <p>
 * Spring 5.x：AbstractClassGenerator.CACHE 是 Map<ClassLoader, ?>；
 * Spring 6.x：缓存结构已重构，走通用兜底扫描。
 */
@Slf4j
final class CglibCacheCleaner {

    private final int springMajorVersion;

    CglibCacheCleaner(int springMajorVersion) {
        this.springMajorVersion = springMajorVersion;
    }

    void clear(String lingId, ClassLoader lingClassLoader) {
        if (springMajorVersion >= 6) {
            clearCglibCacheGeneric(lingId, lingClassLoader);
        } else {
            clearCglibCacheV5(lingId, lingClassLoader);
        }
    }

    /** 面向 Spring 5.x / Boot 2.x：AbstractClassGenerator.CACHE 静态字段 */
    private void clearCglibCacheV5(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName("org.springframework.cglib.core.AbstractClassGenerator");
            Field f = c.getDeclaredField("CACHE");
            f.setAccessible(true);
            Object cache = f.get(null);
            if (cache instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) cache;
                Object removed = map.remove(lingClassLoader);
                if (removed != null) {
                    log.info("[{}] Cleared CGLIB CACHE entry (Spring 5.x)", lingId);
                }
            }
        } catch (NoSuchFieldException e) {
            // 不是预期的 Spring 5 结构，走兜底
            clearCglibCacheGeneric(lingId, lingClassLoader);
        } catch (Exception e) {
            log.debug("[{}] CGLIB V5 cache cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /** 通用兜底：扫描 AbstractClassGenerator 所有静态 Map 字段 */
    private void clearCglibCacheGeneric(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName("org.springframework.cglib.core.AbstractClassGenerator");
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(f.getType()))
                    continue;
                try {
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(null);
                    if (map != null) {
                        Object removed = map.remove(lingClassLoader);
                        if (removed != null) {
                            log.info("[{}] Cleared CGLIB cache field '{}' (generic)", lingId, f.getName());
                        }
                    }
                } catch (Exception e) {
                    log.trace("[{}] Failed to clear CGLIB field {}: {}", lingId, f.getName(), e.getMessage());
                }
            }
        } catch (ClassNotFoundException e) {
            log.trace("[{}] CGLIB not on classpath", lingId);
        } catch (Exception e) {
            log.debug("[{}] CGLIB generic cache cleanup failed: {}", lingId, e.getMessage());
        }
    }
}
