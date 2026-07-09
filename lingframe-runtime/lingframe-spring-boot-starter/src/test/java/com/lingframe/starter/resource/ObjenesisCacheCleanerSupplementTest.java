package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link ObjenesisCacheCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖三种清理策略（ObjenesisCglibAopProxy / SpringObjenesis / BaseInstantiatorStrategy）
 * 在不同 ClassLoader 输入下的容错行为。
 */
@DisplayName("ObjenesisCacheCleaner 补充测试")
class ObjenesisCacheCleanerSupplementTest {

    @Test
    @DisplayName("null ClassLoader 下应安全执行所有三套策略")
    void shouldHandleNullClassLoader() {
        ObjenesisCacheCleaner cleaner = new ObjenesisCacheCleaner();

        // clear 方法不检查 null，但底层 SpringCleanupSupport 方法均处理 null
        assertDoesNotThrow(() -> cleaner.clear("ling-a", null));
    }

    @Test
    @DisplayName("系统 ClassLoader 下应安全执行清理")
    void shouldCleanupWithSystemClassLoader() {
        ObjenesisCacheCleaner cleaner = new ObjenesisCacheCleaner();
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        // ObjenesisCglibAopProxy / SpringObjenesis 在 Spring 类路径上，反射不应抛异常
        assertDoesNotThrow(() -> cleaner.clear("ling-a", systemCl));
    }

    @Test
    @DisplayName("自定义 ClassLoader 下应安全执行清理")
    void shouldCleanupWithCustomClassLoader() {
        ObjenesisCacheCleaner cleaner = new ObjenesisCacheCleaner();
        ClassLoader customCl = new ClassLoader() {
        };

        assertDoesNotThrow(() -> cleaner.clear("ling-a", customCl));
    }

    @Test
    @DisplayName("lingId 为 null 时也应安全执行")
    void shouldHandleNullLingId() {
        ObjenesisCacheCleaner cleaner = new ObjenesisCacheCleaner();
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> cleaner.clear(null, cl));
    }

    @Test
    @DisplayName("多次调用 clear 应幂等不抛异常")
    void shouldBeIdempotent() {
        ObjenesisCacheCleaner cleaner = new ObjenesisCacheCleaner();
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> {
            cleaner.clear("ling-a", cl);
            cleaner.clear("ling-a", cl);
            cleaner.clear("ling-a", cl);
        });
    }
}
