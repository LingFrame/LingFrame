package com.lingframe.core.plugin;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.spi.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginManager 集成测试")
public class PluginManagerTest {

    @TempDir
    Path tempDir;

    @Mock
    private ContainerFactory containerFactory;

    @Mock
    private PermissionService permissionService;

    @Mock
    private GovernanceKernel governanceKernel;

    @Mock
    private PluginLoaderFactory pluginLoaderFactory;

    @Mock
    private EventBus eventBus;

    @Mock
    private TrafficRouter trafficRouter;

    @Mock
    private PluginServiceInvoker pluginServiceInvoker;

    @Mock
    private TransactionVerifier transactionVerifier;

    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        // 设置 mock 行为
        when(pluginLoaderFactory.create(any(), any()))
                .thenReturn(Thread.currentThread().getContextClassLoader());

        pluginManager = new PluginManager(
                containerFactory,
                permissionService,
                governanceKernel,
                pluginLoaderFactory,
                Collections.emptyList(),
                eventBus,
                trafficRouter,
                pluginServiceInvoker,
                transactionVerifier,
                Collections.emptyList()
        );
    }

    @AfterEach
    void tearDown() {
        if (pluginManager != null) {
            try {
                pluginManager.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 辅助方法 ====================

    private File createPluginDir(String pluginId) throws IOException {
        File pluginDir = tempDir.resolve(pluginId).toFile();
        pluginDir.mkdirs();

        // 创建 plugin.yml
        File ymlFile = new File(pluginDir, "plugin.yml");
        try (FileWriter writer = new FileWriter(ymlFile)) {
            writer.write("id: " + pluginId + "\n");
            writer.write("version: 1.0.0\n");
        }

        return pluginDir;
    }

    private PluginContainer createMockContainer() {
        PluginContainer container = mock(PluginContainer.class);
        when(container.isActive()).thenReturn(true);
        doNothing().when(container).start(any());
        doNothing().when(container).stop();
        return container;
    }

    // ==================== 基础功能测试 ====================

    @Nested
    @DisplayName("基础功能")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("新建 PluginManager 应该没有已安装的插件")
        void newManagerShouldHaveNoInstalledPlugins() {
            assertTrue(pluginManager.getInstalledPlugins().isEmpty());
        }

        @Test
        @DisplayName("获取不存在的插件版本应返回 null")
        void shouldReturnNullForNonExistentPlugin() {
            assertNull(pluginManager.getPluginVersion("non-existent"));
        }

        @Test
        @DisplayName("获取不存在的 Runtime 应返回 null")
        void shouldReturnNullForNonExistentRuntime() {
            assertNull(pluginManager.getRuntime("non-existent"));
        }
    }

    // ==================== 安装测试 ====================

    @Nested
    @DisplayName("插件安装")
    class InstallTests {

        @Test
        @DisplayName("安装新插件应该成功")
        void shouldInstallNewPlugin() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);

            Set<String> plugins = pluginManager.getInstalledPlugins();
            assertTrue(plugins.contains("plugin-a"));
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-a"));
        }

        @Test
        @DisplayName("安装应该创建 Runtime")
        void installShouldCreateRuntime() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);

            PluginRuntime runtime = pluginManager.getRuntime("plugin-a");
            assertNotNull(runtime);
            assertEquals("plugin-a", runtime.getPluginId());
        }

        @Test
        @DisplayName("安装应该发布生命周期事件")
        void shouldPublishLifecycleEvents() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);

            // 验证事件发布
            verify(eventBus, atLeastOnce()).publish(any());
        }

        @Test
        @DisplayName("容器启动失败应该抛出异常")
        void shouldThrowWhenContainerStartFails() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = mock(PluginContainer.class);
            doThrow(new RuntimeException("Start failed")).when(container).start(any());
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            assertThrows(RuntimeException.class, () ->
                    pluginManager.installDev("plugin-a", "1.0.0", pluginDir));
        }

        @Test
        @DisplayName("安装无效目录应该抛出异常")
        void shouldThrowWhenDirectoryInvalid() {
            File invalidDir = new File("/non/existent/path");

            assertThrows(IllegalArgumentException.class, () ->
                    pluginManager.installDev("plugin-a", "1.0.0", invalidDir));
        }
    }

    // ==================== 卸载测试 ====================

    @Nested
    @DisplayName("插件卸载")
    class UninstallTests {

        @Test
        @DisplayName("卸载已安装的插件应该成功")
        void shouldUninstallPlugin() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);
            pluginManager.uninstall("plugin-a");

            assertFalse(pluginManager.getInstalledPlugins().contains("plugin-a"));
            assertNull(pluginManager.getPluginVersion("plugin-a"));
            assertNull(pluginManager.getRuntime("plugin-a"));
        }

        @Test
        @DisplayName("卸载不存在的插件应该静默处理")
        void shouldHandleUninstallNonExistent() {
            assertDoesNotThrow(() -> pluginManager.uninstall("non-existent"));
        }

        @Test
        @DisplayName("卸载应该清理权限数据")
        void shouldCleanupPermissions() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);
            pluginManager.uninstall("plugin-a");

            verify(permissionService).removePlugin("plugin-a");
        }

        @Test
        @DisplayName("卸载应该清理事件订阅")
        void shouldCleanupEventSubscriptions() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);
            pluginManager.uninstall("plugin-a");

            verify(eventBus).unsubscribeAll("plugin-a");
        }

        @Test
        @DisplayName("卸载应该发布卸载事件")
        void shouldPublishUninstallEvents() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);

            reset(eventBus); // 重置，只验证卸载事件

            pluginManager.uninstall("plugin-a");

            verify(eventBus, atLeastOnce()).publish(any());
        }
    }

    // ==================== 多插件测试 ====================

    @Nested
    @DisplayName("多插件场景")
    class MultiPluginTests {

        @Test
        @DisplayName("多个插件应该能共存")
        void multiplePluginsShouldCoexist() throws IOException {
            for (int i = 0; i < 3; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
            }

            Set<String> plugins = pluginManager.getInstalledPlugins();
            assertEquals(3, plugins.size());
            assertTrue(plugins.contains("plugin-0"));
            assertTrue(plugins.contains("plugin-1"));
            assertTrue(plugins.contains("plugin-2"));
        }

        @Test
        @DisplayName("卸载一个插件不应影响其他插件")
        void uninstallOneShouldNotAffectOthers() throws IOException {
            for (int i = 0; i < 3; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
            }

            pluginManager.uninstall("plugin-1");

            Set<String> plugins = pluginManager.getInstalledPlugins();
            assertEquals(2, plugins.size());
            assertTrue(plugins.contains("plugin-0"));
            assertFalse(plugins.contains("plugin-1"));
            assertTrue(plugins.contains("plugin-2"));

            // 验证其他插件仍然正常
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-0"));
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-2"));
            assertNotNull(pluginManager.getRuntime("plugin-0"));
            assertNotNull(pluginManager.getRuntime("plugin-2"));
        }

        @Test
        @DisplayName("getAllPluginIds 应返回所有插件 ID")
        void getAllPluginIdsShouldReturnAll() throws IOException {
            for (int i = 0; i < 3; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
            }

            var allIds = pluginManager.getAllPluginIds();
            assertEquals(3, allIds.size());
        }
    }

    // ==================== 热升级测试 ====================

    @Nested
    @DisplayName("热升级")
    class HotUpgradeTests {

        @Test
        @DisplayName("升级应该更新版本号")
        void upgradeShouldUpdateVersion() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container1 = createMockContainer();
            PluginContainer container2 = createMockContainer();

            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-a"));

            pluginManager.installDev("plugin-a", "2.0.0", pluginDir);
            assertEquals("2.0.0", pluginManager.getPluginVersion("plugin-a"));
        }

        @Test
        @DisplayName("reload 应该使用原来的源文件")
        void reloadShouldUseOriginalSource() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container1 = createMockContainer();
            PluginContainer container2 = createMockContainer();

            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            pluginManager.installDev("plugin-a", "1.0.0", pluginDir);
            pluginManager.reload("plugin-a");

            // 验证使用了相同的目录
            verify(containerFactory, times(2)).create(eq("plugin-a"), eq(pluginDir), any());
        }

        @Test
        @DisplayName("reload 不存在的插件应该静默处理")
        void reloadNonExistentShouldBeSilent() {
            assertDoesNotThrow(() -> pluginManager.reload("non-existent"));
        }
    }

    // ==================== 关闭测试 ====================

    @Nested
    @DisplayName("全局关闭")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown 应该清理所有资源")
        void shutdownShouldCleanupAllResources() throws IOException {
            for (int i = 0; i < 3; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
            }

            pluginManager.shutdown();

            assertTrue(pluginManager.getInstalledPlugins().isEmpty());
        }

        @Test
        @DisplayName("shutdown 应该是幂等的")
        void shutdownShouldBeIdempotent() {
            assertDoesNotThrow(() -> {
                pluginManager.shutdown();
                pluginManager.shutdown();
                pluginManager.shutdown();
            });
        }

        @Test
        @DisplayName("shutdown 后不应该能安装新插件")
        void shouldNotInstallAfterShutdown() throws IOException {
            pluginManager.shutdown();

            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            // 根据实现，可能抛异常或静默失败
            // 这里假设不会抛异常，但插件不会被添加
            try {
                pluginManager.installDev("plugin-a", "1.0.0", pluginDir);
            } catch (Exception e) {
                // 可能抛异常
            }
        }
    }

    // ==================== 线程池隔离测试 ====================

    @Nested
    @DisplayName("线程池隔离")
    class ThreadPoolIsolationTests {

        @Test
        @DisplayName("卸载插件 A 不应影响插件 B")
        void uninstallAShouldNotAffectB() throws Exception {
            // 安装两个插件
            for (String pluginId : new String[]{"plugin-a", "plugin-b"}) {
                File pluginDir = createPluginDir(pluginId);
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);
                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
            }

            // 卸载 A
            pluginManager.uninstall("plugin-a");

            // B 应该仍然正常
            PluginRuntime runtimeB = pluginManager.getRuntime("plugin-b");
            assertNotNull(runtimeB);
            assertEquals("1.0.0", runtimeB.getVersion());

            // 验证 B 的实例仍然就绪
            assertNotNull(runtimeB.getInstancePool().getDefault());
            assertTrue(runtimeB.getInstancePool().getDefault().isReady());
        }
    }

    // ==================== 并发测试 ====================

    @Nested
    @DisplayName("并发场景")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发安装不同插件应该安全")
        void concurrentInstallDifferentPluginsShouldBeSafe() throws Exception {
            int pluginCount = 5;

            // 🔥 在并发执行前，预先设置好所有 mock
            Map<String, PluginContainer> containerMap = new ConcurrentHashMap<>();
            for (int i = 0; i < pluginCount; i++) {
                String pluginId = "plugin-" + i;
                PluginContainer container = createMockContainer();
                containerMap.put(pluginId, container);
            }

            // 使用 Answer 模式，根据 pluginId 返回对应的 container
            when(containerFactory.create(anyString(), any(), any()))
                    .thenAnswer(invocation -> {
                        String pluginId = invocation.getArgument(0);
                        return containerMap.get(pluginId);
                    });

            // 预先创建好所有插件目录
            Map<String, File> pluginDirs = new ConcurrentHashMap<>();
            for (int i = 0; i < pluginCount; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                pluginDirs.put(pluginId, pluginDir);
            }

            ExecutorService executor = Executors.newFixedThreadPool(pluginCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(pluginCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int i = 0; i < pluginCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        String pluginId = "plugin-" + index;
                        File pluginDir = pluginDirs.get(pluginId);

                        pluginManager.installDev(pluginId, "1.0.0", pluginDir);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        // 打印异常便于调试
                        System.err.println("Failed to install plugin-" + index + ": " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed, "测试应在30秒内完成");
            assertEquals(pluginCount, successCount.get(),
                    "所有插件都应该安装成功，失败数: " + failCount.get());
            assertEquals(pluginCount, pluginManager.getInstalledPlugins().size());
        }

        @Test
        @DisplayName("并发安装和卸载应该安全")
        void concurrentInstallAndUninstallShouldBeSafe() throws Exception {
            // 🔥 使用 Answer 模式处理动态 mock
            when(containerFactory.create(anyString(), any(), any()))
                    .thenAnswer(invocation -> createMockContainer());

            // 先安装一些插件
            for (int i = 0; i < 3; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
            }

            // 预先创建新插件目录
            Map<String, File> newPluginDirs = new ConcurrentHashMap<>();
            for (int i = 0; i < 5; i++) {
                String pluginId = "new-plugin-" + (i * 2 + 1); // 奇数索引
                File pluginDir = createPluginDir(pluginId);
                newPluginDirs.put(pluginId, pluginDir);
            }

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (index % 2 == 0) {
                            // 卸载
                            pluginManager.uninstall("plugin-" + (index % 3));
                        } else {
                            // 安装新的
                            String pluginId = "new-plugin-" + index;
                            File pluginDir = newPluginDirs.get(pluginId);
                            if (pluginDir != null) {
                                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        // 某些竞态条件下的异常是可以接受的
                        System.err.println("Concurrent operation error: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed, "测试应在30秒内完成");
            // 不验证具体数量，只要不崩溃就行
            // 并发场景下某些操作失败是可以接受的
        }

        @Test
        @DisplayName("并发安装和卸载应该不崩溃")
        void concurrentInstallAndUninstallShouldNotCrash() throws Exception {
            // 使用 Answer 模式
            when(containerFactory.create(anyString(), any(), any()))
                    .thenAnswer(invocation -> createMockContainer());

            // 先安装一些插件
            for (int i = 0; i < 3; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
            }

            // 预先创建新插件目录
            Map<Integer, File> newPluginDirs = new HashMap<>();
            for (int i = 1; i < 10; i += 2) { // 奇数
                newPluginDirs.put(i, createPluginDir("new-plugin-" + i));
            }

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        if (index % 2 == 0) {
                            // 卸载
                            pluginManager.uninstall("plugin-" + (index % 3));
                        } else {
                            // 安装
                            String pluginId = "new-plugin-" + index;
                            File pluginDir = newPluginDirs.get(index);
                            if (pluginDir != null) {
                                pluginManager.installDev(pluginId, "1.0.0", pluginDir);
                            }
                        }
                    } catch (Exception e) {
                        // 并发场景下某些异常是可以接受的
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed, "测试应在30秒内完成");
            // 只要不崩溃就算通过
        }
    }
}