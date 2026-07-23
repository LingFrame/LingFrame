package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodClassKey;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpringStaticCacheCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖 clearStablePublicCaches / clearPropertyAnnotationCache /
 * clearSpringFactoriesCache 在各种 ClassLoader 输入下的容错行为，
 * 以及 BridgeMethodResolver 同步排空语义。
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

    @Test
    @DisplayName("clearStablePublicCaches 应排空 BridgeMethodResolver 中关联目标 CL 的 MethodClassKey")
    void shouldDrainBridgeMethodResolverEntriesForTargetClassLoader() throws Exception {
        // 用独立 URLClassLoader 再定义 FakeLingMarker，模拟灵元 defining CL
        // parent = 平台/扩展加载器，避免直接命中 AppClassLoader 上已加载的同名类
        URL location = FakeLingMarker.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader platformParent = ClassLoader.getSystemClassLoader().getParent();
        try (java.net.URLClassLoader fakeLingCl = new java.net.URLClassLoader(
                new URL[]{location}, platformParent)) {
            Class<?> markerClass = Class.forName(FakeLingMarker.class.getName(), true, fakeLingCl);
            assertTrue(markerClass.getClassLoader() == fakeLingCl,
                    "标记类应由独立 ClassLoader 定义");

            Method anyMethod = String.class.getMethod("length");
            MethodClassKey key = new MethodClassKey(anyMethod, markerClass);

            Map<Object, Object> cache = bridgeMethodResolverCache();
            cache.put(key, anyMethod);
            assertTrue(cache.containsKey(key), "预置 MethodClassKey 应进入 BridgeMethodResolver.cache");

            SpringStaticCacheCleaner cleaner = new SpringStaticCacheCleaner();
            cleaner.clearStablePublicCaches("fake-ling", fakeLingCl);

            assertFalse(cacheContainsRelatedKey(cache, fakeLingCl),
                    "清理后 cache 中不应再有关联目标 CL 的 MethodClassKey");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> bridgeMethodResolverCache() throws Exception {
        Field f = BridgeMethodResolver.class.getDeclaredField("cache");
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(null);
    }

    private static boolean cacheContainsRelatedKey(Map<?, ?> cache, ClassLoader cl) {
        for (Map.Entry<?, ?> e : cache.entrySet()) {
            if (SpringCleanupSupport.isRelatedToClassLoader(e.getKey(), cl)
                    || SpringCleanupSupport.isRelatedToClassLoader(e.getValue(), cl)) {
                return true;
            }
        }
        return false;
    }
}
