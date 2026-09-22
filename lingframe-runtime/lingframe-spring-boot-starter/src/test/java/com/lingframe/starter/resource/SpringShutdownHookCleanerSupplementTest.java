package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpringShutdownHookCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖 clear 主路径容错、null context 短路、
 * 已注册 shutdownHook 的移除与字段清空。
 */
@DisplayName("SpringShutdownHookCleaner 补充测试")
class SpringShutdownHookCleanerSupplementTest {

    @Test
    @DisplayName("clear 在系统 ClassLoader 下应安全执行")
    void shouldSafelyClearWithSystemClassLoader() {
        SpringShutdownHookCleaner cleaner = new SpringShutdownHookCleaner();
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", systemCl));
    }

    @Test
    @DisplayName("clear 在自定义 ClassLoader 下应安全执行")
    void shouldSafelyClearWithCustomClassLoader() {
        SpringShutdownHookCleaner cleaner = new SpringShutdownHookCleaner();
        ClassLoader customCl = new ClassLoader() {
        };

        assertDoesNotThrow(() -> cleaner.clear("ling-a", customCl));
    }

    @Test
    @DisplayName("clearApplicationContextShutdownHook 在 null context 下应安全返回")
    void shouldReturnWhenContextNull() {
        SpringShutdownHookCleaner cleaner = new SpringShutdownHookCleaner();

        assertDoesNotThrow(() -> cleaner.clearApplicationContextShutdownHook("ling-a", null));
    }

    @Test
    @DisplayName("clearApplicationContextShutdownHook 在未注册 shutdownHook 的 context 下应安全执行")
    void shouldSafelyClearContextWithoutRegisteredHook() {
        SpringShutdownHookCleaner cleaner = new SpringShutdownHookCleaner();
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            // 未调用 registerShutdownHook，shutdownHook 字段为 null，应安全返回
            assertDoesNotThrow(() -> cleaner.clearApplicationContextShutdownHook("ling-a", context));
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("clearApplicationContextShutdownHook 在已注册 shutdownHook 时应移除并清空字段")
    void shouldRemoveRegisteredShutdownHook() throws Exception {
        SpringShutdownHookCleaner cleaner = new SpringShutdownHookCleaner();
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        context.registerShutdownHook();
        try {
            // 清理前应能找到 shutdownHook 字段且为 Thread 实例
            Field hookField = SpringCleanupSupport.findFieldInHierarchy(context.getClass(), "shutdownHook");
            assertNotNull(hookField);
            hookField.setAccessible(true);
            Object hookBefore = hookField.get(context);
            assertTrue(hookBefore instanceof Thread);

            cleaner.clearApplicationContextShutdownHook("ling-a", context);

            // 清理后字段应被置为 null
            assertNull(hookField.get(context));
        } finally {
            // shutdownHook 已被清空，close 时不会重复移除
            context.close();
        }
    }
}
