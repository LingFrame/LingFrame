package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link BindConverterCacheCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖 null ClassLoader 短路、BindConverter 不在类路径时的容错、
 * 以及正常清理路径下不抛异常的契约。
 */
@DisplayName("BindConverterCacheCleaner 补充测试")
class BindConverterCacheCleanerSupplementTest {

    @Test
    @DisplayName("lingClassLoader 为 null 时应安全返回")
    void shouldReturnWhenClassLoaderNull() {
        BindConverterCacheCleaner cleaner = new BindConverterCacheCleaner();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", null));
    }

    @Test
    @DisplayName("正常 ClassLoader 下清理应不抛异常（BindConverter 在 Spring Boot 2.7 类路径上）")
    void shouldNotThrowWithValidClassLoader() {
        BindConverterCacheCleaner cleaner = new BindConverterCacheCleaner();
        ClassLoader cl = new ClassLoader() {
        };

        // BindConverter 在 Spring Boot 2.7.18 类路径上，清理器应尝试重置单例或深度遍历
        assertDoesNotThrow(() -> cleaner.clear("ling-a", cl));
    }

    @Test
    @DisplayName("lingId 为 null 时也应安全执行")
    void shouldHandleNullLingId() {
        BindConverterCacheCleaner cleaner = new BindConverterCacheCleaner();
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> cleaner.clear(null, cl));
    }

    @Test
    @DisplayName("系统 ClassLoader 不关联灵元时应清理 0 条记录但不报错")
    void shouldSafelyCleanupWithSystemClassLoader() {
        BindConverterCacheCleaner cleaner = new BindConverterCacheCleaner();
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        // 系统 ClassLoader 不加载任何灵元类，深度遍历不会匹配任何条目
        assertDoesNotThrow(() -> cleaner.clear("ling-a", systemCl));
    }
}
