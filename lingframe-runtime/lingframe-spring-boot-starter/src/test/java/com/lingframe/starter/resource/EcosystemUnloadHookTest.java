package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("生态卸载钩子测试")
class EcosystemUnloadHookTest {

    @Nested
    @DisplayName("SpringEcosystemUnloadHook 测试")
    class EcosystemHookTest {

        private final SpringEcosystemUnloadHook hook = new SpringEcosystemUnloadHook();

        @Test
        @DisplayName("cleanup null ClassLoader 不报错")
        void shouldHandleNullClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", null));
        }

        @Test
        @DisplayName("cleanup 自定义 ClassLoader 不报错")
        void shouldCleanupCustomClassLoader() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> hook.cleanup("ling-a", customCL));
        }

        @Test
        @DisplayName("clearContexts 不报错")
        void shouldClearContexts() {
            assertDoesNotThrow(hook::clearContexts);
        }

        @Test
        @DisplayName("preCleanup 无 Context 不报错")
        void shouldPreCleanupWithoutContext() {
            assertDoesNotThrow(() -> hook.preCleanup("ling-a"));
        }
    }

    @Nested
    @DisplayName("StorageCacheUnloadHook 测试")
    class StorageHookTest {

        private final StorageCacheUnloadHook hook = new StorageCacheUnloadHook();

        @Test
        @DisplayName("cleanup 自定义 ClassLoader 不报错")
        void shouldCleanupCustomClassLoader() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> hook.cleanup("ling-a", customCL));
        }
    }
}
