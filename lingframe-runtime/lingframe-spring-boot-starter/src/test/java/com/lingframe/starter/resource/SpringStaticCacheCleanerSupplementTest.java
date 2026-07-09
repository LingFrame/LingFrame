package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link SpringStaticCacheCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖 clearStablePublicCaches / clearPropertyAnnotationCache /
 * clearSpringFactoriesCache 在各种 ClassLoader 输入下的容错行为。
 */
@DisplayName("SpringStaticCacheCleaner 补充测试")
class SpringStaticCacheCleanerSupplementTest {

    @Test
    @DisplayName("clearStablePublicCaches 在系统 ClassLoader 下应安全执行")
    void shouldSafelyClearStableCachesWithSystemClassLoader() {
        SpringStaticCacheCleaner cleaner = new SpringStaticCacheCleaner();
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clearStablePublicCaches("ling-a", systemCl));
    }

    @Test
    @DisplayName("clearStablePublicCaches 在自定义 ClassLoader 下应安全执行")
    void shouldSafelyClearStableCachesWithCustomClassLoader() {
        SpringStaticCacheCleaner cleaner = new SpringStaticCacheCleaner();
        ClassLoader customCl = new ClassLoader() {
        };

        assertDoesNotThrow(() -> cleaner.clearStablePublicCaches("ling-a", customCl));
    }

    @Test
    @DisplayName("clearPropertyAnnotationCache 在系统 ClassLoader 下应安全执行")
    void shouldSafelyClearPropertyAnnotationCache() {
        SpringStaticCacheCleaner cleaner = new SpringStaticCacheCleaner();
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clearPropertyAnnotationCache("ling-a", systemCl, "preCleanup"));
    }

    @Test
    @DisplayName("clearSpringFactoriesCache 在系统 ClassLoader 下应安全执行")
    void shouldSafelyClearSpringFactoriesCache() {
        SpringStaticCacheCleaner cleaner = new SpringStaticCacheCleaner();
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clearSpringFactoriesCache("ling-a", systemCl));
    }

    @Test
    @DisplayName("null lingId 下所有方法应安全执行")
    void shouldHandleNullLingId() {
        SpringStaticCacheCleaner cleaner = new SpringStaticCacheCleaner();
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> {
            cleaner.clearStablePublicCaches(null, cl);
            cleaner.clearPropertyAnnotationCache(null, cl, "cleanup");
            cleaner.clearSpringFactoriesCache(null, cl);
        });
    }
}
