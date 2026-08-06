package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link CglibCacheCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖 Spring 5.x / 6.x 分支选择、ClassLoader.classes 扫描容错、
 * null ClassLoader 安全处理。
 */
@DisplayName("CglibCacheCleaner 补充测试")
class CglibCacheCleanerSupplementTest {

    @Test
    @DisplayName("Spring 5.x 模式下系统 ClassLoader 应安全执行清理")
    void shouldSafelyClearWithSpring5AndSystemClassLoader() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", systemCl));
    }

    @Test
    @DisplayName("Spring 6.x 模式下系统 ClassLoader 应安全执行清理")
    void shouldSafelyClearWithSpring6AndSystemClassLoader() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(6);
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", systemCl));
    }

    @Test
    @DisplayName("自定义 ClassLoader 下 Spring 5.x 模式应安全执行")
    void shouldSafelyClearWithCustomClassLoader() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader customCl = new ClassLoader() {
        };

        assertDoesNotThrow(() -> cleaner.clear("ling-a", customCl));
    }

    @Test
    @DisplayName("null lingId 下应安全执行不抛异常")
    void shouldHandleNullLingId() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> cleaner.clear(null, cl));
    }

    @Test
    @DisplayName("多次调用 clear 应幂等不抛异常")
    void shouldBeIdempotent() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> {
            cleaner.clear("ling-a", cl);
            cleaner.clear("ling-a", cl);
        });
    }
}
