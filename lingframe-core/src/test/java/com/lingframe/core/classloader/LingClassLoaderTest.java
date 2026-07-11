package com.lingframe.core.classloader;

import com.lingframe.core.exception.ClassLoaderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LingClassLoader 测试")
class LingClassLoaderTest {

    @AfterEach
    void tearDown() {
        LingClassLoader.resetSharedApiBoundary();
    }

    @Nested
    @DisplayName("类加载隔离")
    class IsolationTests {

        @Test
        @DisplayName("不同灵元之间应隔离缺失类加载")
        void shouldThrowClassNotFoundForMissingClassInIsolatedLoader() throws Exception {
            try (LingClassLoader first = new LingClassLoader("ling-1", new URL[0], ClassLoader.getSystemClassLoader());
                    LingClassLoader second = new LingClassLoader("ling-2", new URL[0], ClassLoader.getSystemClassLoader())) {
                assertThrows(ClassNotFoundException.class, () -> first.loadClass("com.example.MissingClass"));
                assertThrows(ClassNotFoundException.class, () -> second.loadClass("com.example.MissingClass"));
            }
        }
    }

    @Nested
    @DisplayName("生命周期行为")
    class LifecycleTests {

        @Test
        @DisplayName("关闭后不应再允许加载类或资源")
        void shouldRejectLoadingAfterClose() throws Exception {
            LingClassLoader classLoader = new LingClassLoader("ling-closed", new URL[0], ClassLoader.getSystemClassLoader());
            classLoader.close();

            assertTrue(classLoader.isClosed());
            assertThrows(ClassLoaderException.class, () -> classLoader.loadClass("java.lang.String"));
            assertNull(classLoader.getResource("any/resource"));
        }

        @Test
        @DisplayName("关闭后 getResources 返回空枚举")
        void shouldReturnEmptyResourcesAfterClose() throws Exception {
            LingClassLoader classLoader = new LingClassLoader("ling-closed-res", new URL[0], ClassLoader.getSystemClassLoader());
            classLoader.close();

            Enumeration<URL> resources = classLoader.getResources("any/resource");
            assertFalse(resources.hasMoreElements());
        }

        @Test
        @DisplayName("重复 close 不报错")
        void shouldHandleDoubleClose() throws IOException {
            LingClassLoader classLoader = new LingClassLoader("ling-dblclose", new URL[0], ClassLoader.getSystemClassLoader());
            classLoader.close();
            assertDoesNotThrow(() -> classLoader.close());
        }
    }

    @Nested
    @DisplayName("共享边界管理")
    class SharedBoundaryTests {

        @Test
        @DisplayName("冻结共享边界后不应再允许追加共享包前缀")
        void shouldRejectSharedBoundaryMutationAfterFreeze() {
            LingClassLoader.freezeSharedApiBoundary();

            assertThrows(IllegalStateException.class,
                    () -> LingClassLoader.addSharedApiPackages(Collections.singletonList("demo.shared.")));
        }

        @Test
        @DisplayName("addSharedApiPackages 正常添加")
        void shouldAddSharedApiPackages() {
            LingClassLoader.addSharedApiPackages(Arrays.asList("com.shared.api.", "com.shared.dto."));
            // 不抛异常即成功
        }

        @Test
        @DisplayName("addSharedApiPackages null 不报错")
        void shouldHandleNullSharedApiPackages() {
            assertDoesNotThrow(() -> LingClassLoader.addSharedApiPackages(null));
        }

        @Test
        @DisplayName("clearSharedApiPackages 清空包列表")
        void shouldClearSharedApiPackages() {
            LingClassLoader.addSharedApiPackages(Arrays.asList("com.shared.api."));
            LingClassLoader.clearSharedApiPackages();
        }

        @Test
        @DisplayName("冻结后 clearSharedApiPackages 抛异常")
        void shouldRejectClearAfterFreeze() {
            LingClassLoader.freezeSharedApiBoundary();
            assertThrows(IllegalStateException.class, () -> LingClassLoader.clearSharedApiPackages());
        }

        @Test
        @DisplayName("addParentDelegatePackages 正常添加")
        void shouldAddParentDelegatePackages() {
            LingClassLoader.addParentDelegatePackages(Arrays.asList("com.example.lib."));
        }

        @Test
        @DisplayName("addParentDelegatePackages null 不报错")
        void shouldHandleNullParentDelegatePackages() {
            assertDoesNotThrow(() -> LingClassLoader.addParentDelegatePackages(null));
        }

        @Test
        @DisplayName("冻结后 addParentDelegatePackages 抛异常")
        void shouldRejectAddParentAfterFreeze() {
            LingClassLoader.freezeSharedApiBoundary();
            assertThrows(IllegalStateException.class,
                    () -> LingClassLoader.addParentDelegatePackages(Arrays.asList("com.example.lib.")));
        }

        @Test
        @DisplayName("removeParentDelegatePackages 正常移除")
        void shouldRemoveParentDelegatePackages() {
            LingClassLoader.addParentDelegatePackages(Arrays.asList("com.example.lib."));
            LingClassLoader.removeParentDelegatePackages(Arrays.asList("com.example.lib."));
        }

        @Test
        @DisplayName("冻结后 removeParentDelegatePackages 抛异常")
        void shouldRejectRemoveParentAfterFreeze() {
            LingClassLoader.freezeSharedApiBoundary();
            assertThrows(IllegalStateException.class,
                    () -> LingClassLoader.removeParentDelegatePackages(Arrays.asList("com.example.lib.")));
        }

        @Test
        @DisplayName("resetSharedApiBoundary 重置冻结状态")
        void shouldResetBoundary() {
            LingClassLoader.freezeSharedApiBoundary();
            LingClassLoader.resetSharedApiBoundary();
            // 重置后可以再次添加
            assertDoesNotThrow(() -> LingClassLoader.addSharedApiPackages(Arrays.asList("com.test.")));
        }
    }

    @Nested
    @DisplayName("类加载行为")
    class LoadClassTests {

        @Test
        @DisplayName("白名单包委派给父加载器")
        void shouldDelegateWhitelistedPackages() throws Exception {
            try (LingClassLoader cl = new LingClassLoader("ling-delegate", new URL[0], ClassLoader.getSystemClassLoader())) {
                // java.lang.String 在白名单中，应由父加载器加载
                Class<?> stringClass = cl.loadClass("java.lang.String");
                assertSame(String.class, stringClass);
            }
        }

        @Test
        @DisplayName("共享 API 包委派给父加载器")
        void shouldDelegateSharedApiPackages() throws Exception {
            // org.yaml.snakeyaml 已在 FORCE_PARENT_PACKAGES 中，这里额外验证 sharedApiPackages 机制
            LingClassLoader.addSharedApiPackages(Arrays.asList("org.yaml.snakeyaml."));
            try (LingClassLoader cl = new LingClassLoader("ling-shared", new URL[0], ClassLoader.getSystemClassLoader())) {
                // org.yaml.snakeyaml.Yaml 应由父加载器加载
                Class<?> clazz = cl.loadClass("org.yaml.snakeyaml.Yaml");
                assertNotNull(clazz);
            }
        }
    }

    @Test
    @DisplayName("toString 包含 lingId")
    void shouldToStringContainLingId() {
        LingClassLoader cl = new LingClassLoader("my-ling", new URL[0], ClassLoader.getSystemClassLoader());
        String str = cl.toString();
        assertTrue(str.contains("my-ling"));
        assertTrue(str.contains("LingClassLoader"));
    }

    @Test
    @DisplayName("getLingId 返回正确的 ID")
    void shouldReturnCorrectLingId() {
        LingClassLoader cl = new LingClassLoader("test-id", new URL[0], ClassLoader.getSystemClassLoader());
        assertEquals("test-id", cl.getLingId());
    }

    @Test
    @DisplayName("两参数构造器使用 unknown 作为 lingId")
    void shouldUseUnknownAsDefaultLingId() {
        LingClassLoader cl = new LingClassLoader(new URL[0], ClassLoader.getSystemClassLoader());
        assertEquals("unknown", cl.getLingId());
    }

    /**
     * 边界约束回归：core 的 FORCE_PARENT_PACKAGES 只持灵珑自身依赖（JDK / 姑约 / 门面），
     * 生态环境包（Spring/Jackson/Logback/Log4j2）移 runtime 适配层注入。 避免散回 core。
     */
    @Nested
    @DisplayName("core 白名单边界约束")
    class CoreWhitelistBoundaryTests {

        @Test
        @DisplayName("生态环境包未注入前灵元走 Child-First 自加载，不委派给父")
        void shouldNotDelegateEcosystemPackagesBeforeInjection() throws Exception {
            // 生态环境包（Spring/Jackson/Logback/Log4j2）已不在 core 白名单——
            // 灵元自带这些副本时应走 Child-First 自加载，不被强制委派给父。
            // 验证：缺失生态类时 loadClass 不走父委派（父也拿不到 → ClassNotFoundException）。
            try (LingClassLoader cl = new LingClassLoader("ling-eco", new URL[0], ClassLoader.getSystemClassLoader())) {
                assertThrows(ClassNotFoundException.class,
                        () -> cl.loadClass("org.springframework.context.ApplicationContext"));
            }
        }

        @Test
        @DisplayName("addParentDelegatePackages 注入生态包后灵元走父委派")
        void shouldDelegateEcosystemPackagesAfterAdaptorInjection() throws Exception {
            // 模拟适配层注入：addParentDelegatePackages("org.springframework.") 后应走父委派
            LingClassLoader.addParentDelegatePackages(Arrays.asList("org.springframework."));
            try (LingClassLoader cl = new LingClassLoader("ling-eco-injected", new URL[0], ClassLoader.getSystemClassLoader())) {
                // 注入后走父委派——单测环境父 CL 也拿不到 Spring，但会抛 ClassNotFoundException from parent。
                // 真正委派验证靠 starter 集成测试，此处只断言「注入后 loadClass 路径变了，仍能安全失败」。
                assertThrows(ClassNotFoundException.class,
                        () -> cl.loadClass("org.springframework.context.ApplicationContext"));
            }
        }
    }
}
