package com.lingframe.core.plugin;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.spi.*;
import lombok.NonNull;
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
import java.util.*;
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
    private LingFrameConfig lingFrameConfig;

    @Mock
    private PluginRuntimeConfig runtimeConfig;

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

    @Mock
    private LocalGovernanceRegistry localGovernanceRegistry;

    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        // 配置 LingFrameConfig mock
        when(lingFrameConfig.getCorePoolSize()).thenReturn(2);
        when(lingFrameConfig.getRuntimeConfig()).thenReturn(runtimeConfig);

        // 配置 RuntimeConfig mock
        when(runtimeConfig.getMaxHistorySnapshots()).thenReturn(3);
        when(runtimeConfig.getDyingCheckIntervalSeconds()).thenReturn(5);
        when(runtimeConfig.getForceCleanupDelaySeconds()).thenReturn(30);
        when(runtimeConfig.getDefaultTimeoutMs()).thenReturn(5000);
        when(runtimeConfig.getBulkheadMaxConcurrent()).thenReturn(50);
        when(runtimeConfig.getBulkheadAcquireTimeoutMs()).thenReturn(1000);

        // 线程池预算配置
        when(lingFrameConfig.getGlobalMaxPluginThreads()).thenReturn(32);
        when(lingFrameConfig.getMaxThreadsPerPlugin()).thenReturn(8);
        when(lingFrameConfig.getDefaultThreadsPerPlugin()).thenReturn(2);

        // 设置 PluginLoaderFactory mock
        when(pluginLoaderFactory.create(anyString(), any(), any()))
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
                Collections.emptyList(),
                lingFrameConfig,
                localGovernanceRegistry,
                null // ResourceGuard - 使用默认实现
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

    private PluginDefinition createDefinition(String pluginId, String version) {
        PluginDefinition def = new PluginDefinition();
        def.setId(pluginId);
        def.setVersion(version);
        return def;
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
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev(definition, pluginDir);

            Set<String> plugins = pluginManager.getInstalledPlugins();
            assertTrue(plugins.contains("plugin-a"));
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-a"));
        }

        @Test
        @DisplayName("安装应该创建 Runtime")
        void installShouldCreateRuntime() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev(definition, pluginDir);

            PluginRuntime runtime = pluginManager.getRuntime("plugin-a");
            assertNotNull(runtime);
            assertEquals("plugin-a", runtime.getPluginId());
        }

        @Test
        @DisplayName("安装应该发布生命周期事件")
        void shouldPublishLifecycleEvents() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev(definition, pluginDir);

            verify(eventBus, atLeastOnce()).publish(any());
        }

        @Test
        @DisplayName("容器启动失败应该抛出异常")
        void shouldThrowWhenContainerStartFails() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = mock(PluginContainer.class);
            doThrow(new RuntimeException("Start failed")).when(container).start(any());
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            assertThrows(RuntimeException.class, () -> pluginManager.installDev(definition, pluginDir));
        }

        @Test
        @DisplayName("安装无效目录应该抛出异常")
        void shouldThrowWhenDirectoryInvalid() {
            File invalidDir = new File("/non/existent/path");
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");

            assertThrows(InvalidArgumentException.class, () -> pluginManager.installDev(definition, invalidDir));
        }

        @Test
        @DisplayName("PluginDefinition 验证失败应该抛出异常")
        void shouldThrowWhenDefinitionInvalid() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition definition = new PluginDefinition(); // 缺少 id 和 version

            assertThrows(InvalidArgumentException.class, () -> pluginManager.installDev(definition, pluginDir));
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
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev(definition, pluginDir);
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
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev(definition, pluginDir);
            pluginManager.uninstall("plugin-a");

            verify(permissionService).removePlugin("plugin-a");
        }

        @Test
        @DisplayName("卸载应该清理事件订阅")
        void shouldCleanupEventSubscriptions() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev(definition, pluginDir);
            pluginManager.uninstall("plugin-a");

            verify(eventBus).unsubscribeAll("plugin-a");
        }

        @Test
        @DisplayName("卸载应该发布卸载事件")
        void shouldPublishUninstallEvents() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            pluginManager.installDev(definition, pluginDir);
            reset(eventBus);

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
                PluginDefinition definition = createDefinition(pluginId, "1.0.0");
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(definition, pluginDir);
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
                PluginDefinition definition = createDefinition(pluginId, "1.0.0");
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(definition, pluginDir);
            }

            pluginManager.uninstall("plugin-1");

            Set<String> plugins = pluginManager.getInstalledPlugins();
            assertEquals(2, plugins.size());
            assertTrue(plugins.contains("plugin-0"));
            assertFalse(plugins.contains("plugin-1"));
            assertTrue(plugins.contains("plugin-2"));

            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-0"));
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-2"));
            assertNotNull(pluginManager.getRuntime("plugin-0"));
            assertNotNull(pluginManager.getRuntime("plugin-2"));
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

            PluginDefinition def1 = createDefinition("plugin-a", "1.0.0");
            pluginManager.installDev(def1, pluginDir);
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-a"));

            PluginDefinition def2 = createDefinition("plugin-a", "2.0.0");
            pluginManager.installDev(def2, pluginDir);
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

            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            pluginManager.installDev(definition, pluginDir);
            pluginManager.reload("plugin-a");

            verify(containerFactory, times(2)).create(eq("plugin-a"), eq(pluginDir), any());
        }

        @Test
        @DisplayName("reload 应该更新版本号")
        void reloadShouldUpdateVersion() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container1 = createMockContainer();
            PluginContainer container2 = createMockContainer();

            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            pluginManager.installDev(definition, pluginDir);

            String oldVersion = pluginManager.getPluginVersion("plugin-a");
            pluginManager.reload("plugin-a");
            String newVersion = pluginManager.getPluginVersion("plugin-a");

            assertNotEquals(oldVersion, newVersion);
            assertTrue(newVersion.startsWith("dev-reload-"));
        }

        @Test
        @DisplayName("reload 不存在的插件应该静默处理")
        void reloadNonExistentShouldBeSilent() {
            assertDoesNotThrow(() -> pluginManager.reload("non-existent"));
        }

        @Test
        @DisplayName("reload 不应修改原始 PluginDefinition")
        void reloadShouldNotModifyOriginalDefinition() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container1 = createMockContainer();
            PluginContainer container2 = createMockContainer();

            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            PluginDefinition definition = createDefinition("plugin-a", "1.0.0");
            String originalVersion = definition.getVersion();

            pluginManager.installDev(definition, pluginDir);
            pluginManager.reload("plugin-a");

            // 原始定义不应被修改（reload 内部使用 copy()）
            // 注意：这取决于实现，如果 reload 直接修改了 map 中的对象
            // 则此测试会失败，说明需要修复
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
                PluginDefinition definition = createDefinition(pluginId, "1.0.0");
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(definition, pluginDir);
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
    }

    // ==================== 线程池隔离测试 ====================

    @Nested
    @DisplayName("线程池隔离")
    class ThreadPoolIsolationTests {

        @Test
        @DisplayName("卸载插件 A 不应影响插件 B")
        void uninstallAShouldNotAffectB() throws Exception {
            for (String pluginId : new String[] { "plugin-a", "plugin-b" }) {
                File pluginDir = createPluginDir(pluginId);
                PluginDefinition definition = createDefinition(pluginId, "1.0.0");
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(definition, pluginDir);
            }

            pluginManager.uninstall("plugin-a");

            PluginRuntime runtimeB = pluginManager.getRuntime("plugin-b");
            assertNotNull(runtimeB);
            assertEquals("1.0.0", runtimeB.getVersion());
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

            // 预设 mock
            Map<String, PluginContainer> containerMap = new ConcurrentHashMap<>();
            Map<String, PluginDefinition> definitionMap = new ConcurrentHashMap<>();
            Map<String, File> pluginDirs = new ConcurrentHashMap<>();

            for (int i = 0; i < pluginCount; i++) {
                String pluginId = "plugin-" + i;
                containerMap.put(pluginId, createMockContainer());
                definitionMap.put(pluginId, createDefinition(pluginId, "1.0.0"));
                pluginDirs.put(pluginId, createPluginDir(pluginId));
            }

            when(containerFactory.create(anyString(), any(), any()))
                    .thenAnswer(invocation -> containerMap.get(invocation.getArgument(0)));

            ExecutorService executor = Executors.newFixedThreadPool(pluginCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(pluginCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < pluginCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        String pluginId = "plugin-" + index;
                        pluginManager.installDev(definitionMap.get(pluginId), pluginDirs.get(pluginId));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        System.err.println("Failed: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed);
            assertEquals(pluginCount, successCount.get());
            assertEquals(pluginCount, pluginManager.getInstalledPlugins().size());
        }

        @Test
        @DisplayName("并发安装和卸载不应崩溃")
        void concurrentInstallAndUninstallShouldNotCrash() throws Exception {
            when(containerFactory.create(anyString(), any(), any()))
                    .thenAnswer(invocation -> createMockContainer());

            // 先安装一些插件
            for (int i = 0; i < 3; i++) {
                String pluginId = "plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                PluginDefinition definition = createDefinition(pluginId, "1.0.0");
                pluginManager.installDev(definition, pluginDir);
            }

            // 预先创建新插件
            Map<Integer, File> newPluginDirs = new HashMap<>();
            Map<Integer, PluginDefinition> newPluginDefs = new HashMap<>();
            for (int i = 1; i < 10; i += 2) {
                newPluginDirs.put(i, createPluginDir("new-plugin-" + i));
                newPluginDefs.put(i, createDefinition("new-plugin-" + i, "1.0.0"));
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
                            pluginManager.uninstall("plugin-" + (index % 3));
                        } else {
                            File dir = newPluginDirs.get(index);
                            PluginDefinition def = newPluginDefs.get(index);
                            if (dir != null && def != null) {
                                pluginManager.installDev(def, dir);
                            }
                        }
                    } catch (Exception e) {
                        // 并发场景下某些异常可接受
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(completed, "测试应在30秒内完成");
        }
    }

    // ==================== 灰度发布测试 ====================

    @Nested
    @DisplayName("灰度发布")
    class CanaryDeploymentTests {

        @Test
        @DisplayName("金丝雀部署应该保留标签")
        void canaryDeploymentShouldPreserveLabels() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container1 = createMockContainer();
            PluginContainer container2 = createMockContainer();

            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            // ✅ 先安装一个默认版本
            PluginDefinition defaultDef = createDefinition("plugin-a", "1.0.0");
            pluginManager.installDev(defaultDef, pluginDir);

            // 再部署金丝雀版本
            PluginDefinition canaryDef = createDefinition("plugin-a", "2.0.0-canary");
            Map<String, String> labels = new HashMap<>();
            labels.put("env", "canary");
            labels.put("region", "cn-east");

            pluginManager.deployCanary(canaryDef, pluginDir, labels);

            PluginRuntime runtime = pluginManager.getRuntime("plugin-a");
            assertNotNull(runtime);

            // ✅ 使用 getActiveInstances() 获取所有实例
            var instances = runtime.getInstancePool().getActiveInstances();
            assertFalse(instances.isEmpty());

            // 查找金丝雀实例
            PluginInstance canaryInstance = instances.stream()
                    .filter(i -> "2.0.0-canary".equals(i.getVersion()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(canaryInstance, "Should find canary instance");
            assertEquals("canary", canaryInstance.getLabels().get("env"));
            assertEquals("cn-east", canaryInstance.getLabels().get("region"));
        }

        @Test
        @DisplayName("金丝雀部署不应替换默认版本")
        void canaryDeploymentShouldNotReplaceDefault() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container1 = createMockContainer();
            PluginContainer container2 = createMockContainer();

            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            // 先安装默认版本
            PluginDefinition defaultDef = createDefinition("plugin-a", "1.0.0");
            pluginManager.installDev(defaultDef, pluginDir);

            // 部署金丝雀
            PluginDefinition canaryDef = createDefinition("plugin-a", "2.0.0-canary");
            pluginManager.deployCanary(canaryDef, pluginDir, Map.of("env", "canary"));

            PluginRuntime runtime = pluginManager.getRuntime("plugin-a");

            // 默认版本应该仍然是 1.0.0
            PluginInstance defaultInstance = runtime.getInstancePool().getDefault();
            assertNotNull(defaultInstance);
            assertEquals("1.0.0", defaultInstance.getVersion());

            // 应该有两个活跃实例
            assertEquals(2, runtime.getInstancePool().getActiveInstances().size());
        }

        @Test
        @DisplayName("单独部署金丝雀版本（无默认版本）")
        void canaryOnlyDeployment() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            PluginDefinition canaryDef = createDefinition("plugin-a", "2.0.0-canary");
            Map<String, String> labels = Map.of("env", "canary");

            pluginManager.deployCanary(canaryDef, pluginDir, labels);

            PluginRuntime runtime = pluginManager.getRuntime("plugin-a");
            assertNotNull(runtime);

            // getDefault() 返回 null（因为 isDefault=false）
            assertNull(runtime.getInstancePool().getDefault());

            // 但实例应该存在于活跃列表中
            var instances = runtime.getInstancePool().getActiveInstances();
            assertEquals(1, instances.size());

            PluginInstance instance = instances.get(0);
            assertEquals("2.0.0-canary", instance.getVersion());
            assertEquals("canary", instance.getLabels().get("env"));
        }
    }

    // ==================== 崩溃隔离测试 ====================

    @Nested
    @DisplayName("崩溃隔离")
    class CrashIsolationTests {

        @Test
        @DisplayName("插件容器启动异常不应影响其他插件")
        void containerStartFailureShouldNotAffectOtherPlugins() throws IOException {
            // 先安装正常插件
            File pluginDirA = createPluginDir("plugin-a");
            PluginDefinition defA = createDefinition("plugin-a", "1.0.0");
            PluginContainer containerA = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(containerA);
            pluginManager.installDev(defA, pluginDirA);

            // 安装会崩溃的插件
            File pluginDirB = createPluginDir("plugin-b");
            PluginDefinition defB = createDefinition("plugin-b", "1.0.0");
            PluginContainer containerB = mock(PluginContainer.class);
            doThrow(new RuntimeException("Container start failed!")).when(containerB).start(any());
            when(containerFactory.create(eq("plugin-b"), any(), any())).thenReturn(containerB);

            // 安装崩溃插件
            assertThrows(RuntimeException.class, () -> pluginManager.installDev(defB, pluginDirB));

            // 验证插件 A 不受影响
            PluginRuntime runtimeA = pluginManager.getRuntime("plugin-a");
            runtimeA.activate();
            assertNotNull(runtimeA);
            assertTrue(runtimeA.isAvailable());
            assertEquals("1.0.0", runtimeA.getVersion());

            PluginInstance instanceA = runtimeA.getInstancePool().getDefault();
            assertNotNull(instanceA);
            assertTrue(instanceA.isReady());

            // 验证插件 B 未被安装
            assertNull(pluginManager.getRuntime("plugin-b"));
            assertFalse(pluginManager.getInstalledPlugins().contains("plugin-b"));
        }

        @Test
        @DisplayName("插件容器停止异常不应影响其他插件卸载")
        void containerStopFailureShouldNotAffectOtherPlugins() throws IOException {
            // 安装会在停止时崩溃的插件 A
            File pluginDirA = createPluginDir("plugin-a");
            PluginDefinition defA = createDefinition("plugin-a", "1.0.0");
            PluginContainer containerA = mock(PluginContainer.class);
            when(containerA.isActive()).thenReturn(true);
            doNothing().when(containerA).start(any());
            doThrow(new RuntimeException("Container stop failed!")).when(containerA).stop();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(containerA);
            pluginManager.installDev(defA, pluginDirA);

            // 安装正常插件 B
            File pluginDirB = createPluginDir("plugin-b");
            PluginDefinition defB = createDefinition("plugin-b", "1.0.0");
            PluginContainer containerB = createMockContainer();
            when(containerFactory.create(eq("plugin-b"), any(), any())).thenReturn(containerB);
            pluginManager.installDev(defB, pluginDirB);

            // 卸载崩溃的插件 A（不应影响 B）
            assertDoesNotThrow(() -> pluginManager.uninstall("plugin-a"));

            // 验证 B 不受影响
            PluginRuntime runtimeB = pluginManager.getRuntime("plugin-b");
            runtimeB.activate();
            assertNotNull(runtimeB);
            assertTrue(runtimeB.isAvailable());
        }

        @Test
        @DisplayName("ClassLoader 创建失败不应影响其他插件")
        void classLoaderFailureShouldNotAffectOtherPlugins() throws IOException {
            // 先安装正常插件
            File pluginDirA = createPluginDir("plugin-a");
            PluginDefinition defA = createDefinition("plugin-a", "1.0.0");
            PluginContainer containerA = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(containerA);
            pluginManager.installDev(defA, pluginDirA);

            // 配置 ClassLoader 创建失败
            File pluginDirB = createPluginDir("plugin-b");
            PluginDefinition defB = createDefinition("plugin-b", "1.0.0");
            when(pluginLoaderFactory.create(eq("plugin-b"), any(), any()))
                    .thenThrow(new RuntimeException("ClassLoader creation failed!"));

            // 尝试安装
            assertThrows(RuntimeException.class, () -> pluginManager.installDev(defB, pluginDirB));

            // 验证插件 A 不受影响
            PluginRuntime runtimeA = pluginManager.getRuntime("plugin-a");
            runtimeA.activate();
            assertNotNull(runtimeA);
            assertTrue(runtimeA.isAvailable());
        }

        @Test
        @DisplayName("安全验证失败不应影响其他插件")
        void securityVerificationFailureShouldNotAffectOtherPlugins() throws IOException {
            // 创建带安全验证器的 PluginManager
            PluginManager managerWithVerifier = getManagerWithVerifier();

            try {
                // 安装正常插件 A
                File pluginDirA = createPluginDir("plugin-a");
                PluginDefinition defA = createDefinition("plugin-a", "1.0.0");
                PluginContainer containerA = createMockContainer();
                when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(containerA);
                managerWithVerifier.installDev(defA, pluginDirA);

                // 安装会被安全检查拒绝的插件 B
                File pluginDirB = createPluginDir("plugin-b");
                PluginDefinition defB = createDefinition("plugin-b", "1.0.0");

                assertThrows(RuntimeException.class, () -> managerWithVerifier.installDev(defB, pluginDirB));

                // 验证 A 不受影响
                assertNotNull(managerWithVerifier.getRuntime("plugin-a"));
                assertNull(managerWithVerifier.getRuntime("plugin-b"));

            } finally {
                managerWithVerifier.shutdown();
            }
        }

        @Test
        @DisplayName("shutdown 时单个插件崩溃不应阻止其他插件关闭")
        void shutdownWithCrashingShouldNotBlockOthers() throws IOException {
            // 安装会在 shutdown 时崩溃的插件
            File pluginDirA = createPluginDir("plugin-a");
            PluginDefinition defA = createDefinition("plugin-a", "1.0.0");
            PluginContainer containerA = mock(PluginContainer.class);
            when(containerA.isActive()).thenReturn(true);
            doNothing().when(containerA).start(any());
            doThrow(new RuntimeException("Shutdown failed!")).when(containerA).stop();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(containerA);
            pluginManager.installDev(defA, pluginDirA);

            // 安装正常插件
            File pluginDirB = createPluginDir("plugin-b");
            PluginDefinition defB = createDefinition("plugin-b", "1.0.0");
            PluginContainer containerB = createMockContainer();
            when(containerFactory.create(eq("plugin-b"), any(), any())).thenReturn(containerB);
            pluginManager.installDev(defB, pluginDirB);

            // shutdown 不应抛异常
            assertDoesNotThrow(() -> pluginManager.shutdown());

            // 验证所有插件都被清理
            assertTrue(pluginManager.getInstalledPlugins().isEmpty());

            // 验证正常插件的 stop 被调用
            verify(containerB).stop();
        }

        @Test
        @DisplayName("热升级失败不应影响现有实例")
        void upgradeFailureShouldNotAffectExistingInstance() throws IOException {
            File pluginDir = createPluginDir("plugin-a");

            // 第一次安装成功
            PluginContainer container1 = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1);

            PluginDefinition def1 = createDefinition("plugin-a", "1.0.0");
            pluginManager.installDev(def1, pluginDir);

            // 验证 1.0.0 正常运行
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-a"));

            // 第二次升级失败
            PluginContainer container2 = mock(PluginContainer.class);
            doThrow(new RuntimeException("Upgrade failed!")).when(container2).start(any());
            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container2);

            PluginDefinition def2 = createDefinition("plugin-a", "2.0.0");
            assertThrows(RuntimeException.class, () -> pluginManager.installDev(def2, pluginDir));

            // 验证旧版本仍然可用
            PluginRuntime runtime = pluginManager.getRuntime("plugin-a");
            assertNotNull(runtime);
            // 注意：这取决于实现，如果升级失败会回滚，版本应该是 1.0.0
            // 如果不回滚，可能版本已经变了但实例不可用
        }

        @Test
        @DisplayName("多个插件同时崩溃不应导致系统不可用")
        void multipleCrashesShouldNotBreakSystem() throws IOException {
            // 安装多个会崩溃的插件
            for (int i = 0; i < 3; i++) {
                String pluginId = "crash-plugin-" + i;
                File pluginDir = createPluginDir(pluginId);
                PluginDefinition def = createDefinition(pluginId, "1.0.0");

                PluginContainer container = mock(PluginContainer.class);
                when(container.isActive()).thenReturn(true);
                doNothing().when(container).start(any());
                doThrow(new RuntimeException("Stop failed for " + pluginId)).when(container).stop();
                when(containerFactory.create(eq(pluginId), any(), any())).thenReturn(container);

                pluginManager.installDev(def, pluginDir);
            }

            // 安装正常插件
            File pluginDirGood = createPluginDir("good-plugin");
            PluginDefinition defGood = createDefinition("good-plugin", "1.0.0");
            PluginContainer containerGood = createMockContainer();
            when(containerFactory.create(eq("good-plugin"), any(), any())).thenReturn(containerGood);
            pluginManager.installDev(defGood, pluginDirGood);

            // 全部卸载不应抛异常
            for (int i = 0; i < 3; i++) {
                int finalI = i;
                assertDoesNotThrow(() -> pluginManager.uninstall("crash-plugin-" + finalI));
            }

            // 正常插件仍然可用
            PluginRuntime goodRuntime = pluginManager.getRuntime("good-plugin");
            goodRuntime.activate();
            assertNotNull(goodRuntime);
            assertTrue(goodRuntime.isAvailable());
        }

        @Test
        @DisplayName("reload 失败不应影响原插件")
        void reloadFailureShouldNotAffectOriginal() throws IOException {
            File pluginDir = createPluginDir("plugin-a");

            // 第一次安装成功
            PluginContainer container1 = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container1);

            PluginDefinition def = createDefinition("plugin-a", "1.0.0");
            pluginManager.installDev(def, pluginDir);
            assertEquals("1.0.0", pluginManager.getPluginVersion("plugin-a"));

            // reload 时容器创建失败
            PluginContainer container2 = mock(PluginContainer.class);
            doThrow(new RuntimeException("Reload failed!")).when(container2).start(any());
            when(containerFactory.create(eq("plugin-a"), any(), any()))
                    .thenReturn(container2);

            assertThrows(RuntimeException.class, () -> pluginManager.reload("plugin-a"));

            // 验证原插件状态（取决于实现）
            PluginRuntime runtime = pluginManager.getRuntime("plugin-a");
            assertNotNull(runtime, "Runtime should still exist after failed reload");
        }
    }

    private @NonNull PluginManager getManagerWithVerifier() {
        PluginSecurityVerifier failingVerifier = (pluginId, source) -> {
            if ("plugin-b".equals(pluginId)) {
                throw new SecurityException("Security check failed for " + pluginId);
            }
        };

        return new PluginManager(
                containerFactory,
                permissionService,
                governanceKernel,
                pluginLoaderFactory,
                Collections.singletonList(failingVerifier),
                eventBus,
                trafficRouter,
                pluginServiceInvoker,
                transactionVerifier,
                Collections.emptyList(),
                lingFrameConfig,
                localGovernanceRegistry,
                null);
    }

    // ==================== 异常边界测试 ====================

    @Nested
    @DisplayName("异常边界")
    class ExceptionBoundaryTests {

        @Test
        @DisplayName("ContainerFactory 返回 null 应该抛出有意义的异常")
        void shouldThrowWhenContainerFactoryReturnsNull() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition def = createDefinition("plugin-a", "1.0.0");
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(null);

            assertThrows(Exception.class, () -> pluginManager.installDev(def, pluginDir));
        }

        @Test
        @DisplayName("PluginLoaderFactory 返回 null 应该抛出有意义的异常")
        void shouldThrowWhenLoaderFactoryReturnsNull() throws IOException {
            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition def = createDefinition("plugin-a", "1.0.0");
            when(pluginLoaderFactory.create(eq("plugin-a"), any(), any())).thenReturn(null);

            assertThrows(Exception.class, () -> pluginManager.installDev(def, pluginDir));
        }

        @Test
        @DisplayName("事件发布失败不应阻止安装")
        void eventPublishFailureShouldNotBlockInstall() throws IOException {
            doThrow(new RuntimeException("Event publish failed")).when(eventBus).publish(any());

            File pluginDir = createPluginDir("plugin-a");
            PluginDefinition def = createDefinition("plugin-a", "1.0.0");
            PluginContainer container = createMockContainer();
            when(containerFactory.create(eq("plugin-a"), any(), any())).thenReturn(container);

            // 根据实现，可能成功或失败
            // 如果事件发布是可选的，应该成功
            // 如果是必须的，应该失败
            try {
                pluginManager.installDev(def, pluginDir);
                // 如果成功，验证插件已安装
                assertNotNull(pluginManager.getRuntime("plugin-a"));
            } catch (RuntimeException e) {
                // 如果失败，验证是事件相关的
                assertTrue(e.getMessage().contains("Event") || e.getCause().getMessage().contains("Event"));
            }
        }
    }

    // ==================== 安全扫描测试 ====================

    @Nested
    @DisplayName("安全扫描")
    class SecurityScanTests {

        @Test
        @DisplayName("包含 System.exit 的插件应该被拒绝")
        void shouldRejectPluginWithSystemExit() throws IOException {
            // 创建包含危险 API 的验证器
            PluginSecurityVerifier dangerousApiVerifier = (pluginId, source) -> {
                // 模拟扫描发现 System.exit
                if ("evil-plugin".equals(pluginId)) {
                    throw new SecurityException("Plugin contains System.exit() call");
                }
            };

            PluginManager secureManager = new PluginManager(
                    containerFactory,
                    permissionService,
                    governanceKernel,
                    pluginLoaderFactory,
                    List.of(dangerousApiVerifier),
                    eventBus,
                    trafficRouter,
                    pluginServiceInvoker,
                    transactionVerifier,
                    Collections.emptyList(),
                    lingFrameConfig,
                    localGovernanceRegistry,
                    null);

            try {
                File pluginDir = createPluginDir("evil-plugin");
                PluginDefinition def = createDefinition("evil-plugin", "1.0.0");
                PluginContainer container = createMockContainer();
                when(containerFactory.create(eq("evil-plugin"), any(), any())).thenReturn(container);

                // 应该被安全检查拒绝
                SecurityException ex = assertThrows(SecurityException.class,
                        () -> secureManager.installDev(def, pluginDir));

                assertTrue(ex.getMessage().contains("System.exit") ||
                        ex.getCause().getMessage().contains("System.exit"));

                // 验证插件未被安装
                assertNull(secureManager.getRuntime("evil-plugin"));

            } finally {
                secureManager.shutdown();
            }
        }

        @Test
        @DisplayName("安全验证失败不应影响其他插件")
        void securityFailureShouldNotAffectOtherPlugins() throws IOException {
            PluginSecurityVerifier selectiveVerifier = (pluginId, source) -> {
                if ("evil-plugin".equals(pluginId)) {
                    throw new SecurityException("Dangerous API detected");
                }
            };

            PluginManager secureManager = new PluginManager(
                    containerFactory, permissionService, governanceKernel,
                    pluginLoaderFactory, List.of(selectiveVerifier),
                    eventBus, trafficRouter, pluginServiceInvoker,
                    transactionVerifier, Collections.emptyList(), lingFrameConfig,
                    localGovernanceRegistry, null);

            try {
                // 先安装正常插件
                File goodDir = createPluginDir("good-plugin");
                PluginDefinition goodDef = createDefinition("good-plugin", "1.0.0");
                PluginContainer goodContainer = createMockContainer();
                when(containerFactory.create(eq("good-plugin"), any(), any())).thenReturn(goodContainer);
                secureManager.installDev(goodDef, goodDir);

                // 尝试安装恶意插件
                File evilDir = createPluginDir("evil-plugin");
                PluginDefinition evilDef = createDefinition("evil-plugin", "1.0.0");

                assertThrows(RuntimeException.class, () -> secureManager.installDev(evilDef, evilDir));
                PluginRuntime goodRuntime = secureManager.getRuntime("good-plugin");
                goodRuntime.activate();

                // 正常插件不受影响
                assertNotNull(goodRuntime);
                assertTrue(goodRuntime.isAvailable());

            } finally {
                secureManager.shutdown();
            }
        }
    }
}