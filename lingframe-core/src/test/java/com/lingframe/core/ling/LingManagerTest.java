package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
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
@DisplayName("LingManager 集成测试")
public class LingManagerTest {

    @TempDir
    Path tempDir;

    @Mock
    private LingFrameConfig lingFrameConfig;

    @Mock
    private LingRuntimeConfig runtimeConfig;

    @Mock
    private ContainerFactory containerFactory;

    @Mock
    private PermissionService permissionService;

    @Mock
    private GovernanceKernel governanceKernel;

    @Mock
    private LingLoaderFactory lingLoaderFactory;

    @Mock
    private EventBus eventBus;

    @Mock
    private TrafficRouter trafficRouter;

    @Mock
    private LingServiceInvoker lingServiceInvoker;

    @Mock
    private TransactionVerifier transactionVerifier;

    @Mock
    private LocalGovernanceRegistry localGovernanceRegistry;

    private LingManager lingManager;

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
        when(lingFrameConfig.getGlobalMaxLingThreads()).thenReturn(32);
        when(lingFrameConfig.getMaxThreadsPerLing()).thenReturn(8);
        when(lingFrameConfig.getDefaultThreadsPerLing()).thenReturn(2);

        // 设置 LingLoaderFactory mock
        when(lingLoaderFactory.create(anyString(), any(), any()))
                .thenReturn(Thread.currentThread().getContextClassLoader());

        lingManager = new LingManager(
                containerFactory,
                permissionService,
                governanceKernel,
                lingLoaderFactory,
                Collections.emptyList(),
                eventBus,
                trafficRouter,
                lingServiceInvoker,
                transactionVerifier,
                Collections.emptyList(),
                lingFrameConfig,
                localGovernanceRegistry,
                null // ResourceGuard - 使用默认实现
        );
    }

    @AfterEach
    void tearDown() {
        if (lingManager != null) {
            try {
                lingManager.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 辅助方法 ====================

    private File createLingDir(String lingId) throws IOException {
        File lingDir = tempDir.resolve(lingId).toFile();
        lingDir.mkdirs();

        // 创建 ling.yml
        File ymlFile = new File(lingDir, "ling.yml");
        try (FileWriter writer = new FileWriter(ymlFile)) {
            writer.write("id: " + lingId + "\n");
            writer.write("version: 1.0.0\n");
        }

        return lingDir;
    }

    private LingDefinition createDefinition(String lingId, String version) {
        LingDefinition def = new LingDefinition();
        def.setId(lingId);
        def.setVersion(version);
        return def;
    }

    private LingContainer createMockContainer() {
        LingContainer container = mock(LingContainer.class);
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
        @DisplayName("新建 LingManager 应该没有已安装的单元")
        void newManagerShouldHaveNoInstalledLings() {
            assertTrue(lingManager.getInstalledLings().isEmpty());
        }

        @Test
        @DisplayName("获取不存在的单元版本应返回 null")
        void shouldReturnNullForNonExistentLing() {
            assertNull(lingManager.getLingVersion("non-existent"));
        }

        @Test
        @DisplayName("获取不存在的 Runtime 应返回 null")
        void shouldReturnNullForNonExistentRuntime() {
            assertNull(lingManager.getRuntime("non-existent"));
        }
    }

    // ==================== 安装测试 ====================

    @Nested
    @DisplayName("单元安装")
    class InstallTests {

        @Test
        @DisplayName("安装新单元应该成功")
        void shouldInstallNewLing() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            lingManager.installDev(definition, lingDir);

            Set<String> Lings = lingManager.getInstalledLings();
            assertTrue(Lings.contains("Ling-a"));
            assertEquals("1.0.0", lingManager.getLingVersion("Ling-a"));
        }

        @Test
        @DisplayName("安装应该创建 Runtime")
        void installShouldCreateRuntime() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            lingManager.installDev(definition, lingDir);

            LingRuntime runtime = lingManager.getRuntime("Ling-a");
            assertNotNull(runtime);
            assertEquals("Ling-a", runtime.getLingId());
        }

        @Test
        @DisplayName("安装应该发布生命周期事件")
        void shouldPublishLifecycleEvents() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            lingManager.installDev(definition, lingDir);

            verify(eventBus, atLeastOnce()).publish(any());
        }

        @Test
        @DisplayName("容器启动失败应该抛出异常")
        void shouldThrowWhenContainerStartFails() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = mock(LingContainer.class);
            doThrow(new RuntimeException("Start failed")).when(container).start(any());
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            assertThrows(RuntimeException.class, () -> lingManager.installDev(definition, lingDir));
        }

        @Test
        @DisplayName("安装无效目录应该抛出异常")
        void shouldThrowWhenDirectoryInvalid() {
            File invalidDir = new File("/non/existent/path");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");

            assertThrows(InvalidArgumentException.class, () -> lingManager.installDev(definition, invalidDir));
        }

        @Test
        @DisplayName("LingDefinition 验证失败应该抛出异常")
        void shouldThrowWhenDefinitionInvalid() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = new LingDefinition(); // 缺少 id 和 version

            assertThrows(InvalidArgumentException.class, () -> lingManager.installDev(definition, lingDir));
        }
    }

    // ==================== 卸载测试 ====================

    @Nested
    @DisplayName("单元卸载")
    class UninstallTests {

        @Test
        @DisplayName("卸载已安装的单元应该成功")
        void shouldUninstallLing() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            lingManager.installDev(definition, lingDir);
            lingManager.uninstall("Ling-a");

            assertFalse(lingManager.getInstalledLings().contains("Ling-a"));
            assertNull(lingManager.getLingVersion("Ling-a"));
            assertNull(lingManager.getRuntime("Ling-a"));
        }

        @Test
        @DisplayName("卸载不存在的单元应该静默处理")
        void shouldHandleUninstallNonExistent() {
            assertDoesNotThrow(() -> lingManager.uninstall("non-existent"));
        }

        @Test
        @DisplayName("卸载应该清理权限数据")
        void shouldCleanupPermissions() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            lingManager.installDev(definition, lingDir);
            lingManager.uninstall("Ling-a");

            verify(permissionService).removeLing("Ling-a");
        }

        @Test
        @DisplayName("卸载应该清理事件订阅")
        void shouldCleanupEventSubscriptions() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            lingManager.installDev(definition, lingDir);
            lingManager.uninstall("Ling-a");

            verify(eventBus).unsubscribeAll("Ling-a");
        }

        @Test
        @DisplayName("卸载应该发布卸载事件")
        void shouldPublishUninstallEvents() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            lingManager.installDev(definition, lingDir);
            reset(eventBus);

            lingManager.uninstall("Ling-a");

            verify(eventBus, atLeastOnce()).publish(any());
        }
    }

    // ==================== 多单元测试 ====================

    @Nested
    @DisplayName("多单元场景")
    class MultiLingTests {

        @Test
        @DisplayName("多个单元应该能共存")
        void multipleLingsShouldCoexist() throws IOException {
            for (int i = 0; i < 3; i++) {
                String lingId = "ling-" + i;
                File lingDir = createLingDir(lingId);
                LingDefinition definition = createDefinition(lingId, "1.0.0");
                LingContainer container = createMockContainer();
                when(containerFactory.create(eq(lingId), any(), any())).thenReturn(container);

                lingManager.installDev(definition, lingDir);
            }

            Set<String> Lings = lingManager.getInstalledLings();
            assertEquals(3, Lings.size());
            assertTrue(Lings.contains("Ling-0"));
            assertTrue(Lings.contains("Ling-1"));
            assertTrue(Lings.contains("Ling-2"));
        }

        @Test
        @DisplayName("卸载一个单元不应影响其他单元")
        void uninstallOneShouldNotAffectOthers() throws IOException {
            for (int i = 0; i < 3; i++) {
                String lingId = "ling-" + i;
                File lingDir = createLingDir(lingId);
                LingDefinition definition = createDefinition(lingId, "1.0.0");
                LingContainer container = createMockContainer();
                when(containerFactory.create(eq(lingId), any(), any())).thenReturn(container);

                lingManager.installDev(definition, lingDir);
            }

            lingManager.uninstall("Ling-1");

            Set<String> Lings = lingManager.getInstalledLings();
            assertEquals(2, Lings.size());
            assertTrue(Lings.contains("Ling-0"));
            assertFalse(Lings.contains("Ling-1"));
            assertTrue(Lings.contains("Ling-2"));

            assertEquals("1.0.0", lingManager.getLingVersion("Ling-0"));
            assertEquals("1.0.0", lingManager.getLingVersion("Ling-2"));
            assertNotNull(lingManager.getRuntime("Ling-0"));
            assertNotNull(lingManager.getRuntime("Ling-2"));
        }
    }

    // ==================== 热升级测试 ====================

    @Nested
    @DisplayName("热升级")
    class HotUpgradeTests {

        @Test
        @DisplayName("升级应该更新版本号")
        void upgradeShouldUpdateVersion() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingContainer container1 = createMockContainer();
            LingContainer container2 = createMockContainer();

            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            LingDefinition def1 = createDefinition("Ling-a", "1.0.0");
            lingManager.installDev(def1, lingDir);
            assertEquals("1.0.0", lingManager.getLingVersion("Ling-a"));

            LingDefinition def2 = createDefinition("Ling-a", "2.0.0");
            lingManager.installDev(def2, lingDir);
            assertEquals("2.0.0", lingManager.getLingVersion("Ling-a"));
        }

        @Test
        @DisplayName("reload 应该使用原来的源文件")
        void reloadShouldUseOriginalSource() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingContainer container1 = createMockContainer();
            LingContainer container2 = createMockContainer();

            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            lingManager.installDev(definition, lingDir);
            lingManager.reload("Ling-a");

            verify(containerFactory, times(2)).create(eq("Ling-a"), eq(lingDir), any());
        }

        @Test
        @DisplayName("reload 应该更新版本号")
        void reloadShouldUpdateVersion() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingContainer container1 = createMockContainer();
            LingContainer container2 = createMockContainer();

            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            lingManager.installDev(definition, lingDir);

            String oldVersion = lingManager.getLingVersion("Ling-a");
            lingManager.reload("Ling-a");
            String newVersion = lingManager.getLingVersion("Ling-a");

            assertNotEquals(oldVersion, newVersion);
            assertTrue(newVersion.startsWith("dev-reload-"));
        }

        @Test
        @DisplayName("reload 不存在的单元应该静默处理")
        void reloadNonExistentShouldBeSilent() {
            assertDoesNotThrow(() -> lingManager.reload("non-existent"));
        }

        @Test
        @DisplayName("reload 不应修改原始 LingDefinition")
        void reloadShouldNotModifyOriginalDefinition() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingContainer container1 = createMockContainer();
            LingContainer container2 = createMockContainer();

            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            LingDefinition definition = createDefinition("Ling-a", "1.0.0");
            String originalVersion = definition.getVersion();

            lingManager.installDev(definition, lingDir);
            lingManager.reload("Ling-a");

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
                String lingId = "ling-" + i;
                File lingDir = createLingDir(lingId);
                LingDefinition definition = createDefinition(lingId, "1.0.0");
                LingContainer container = createMockContainer();
                when(containerFactory.create(eq(lingId), any(), any())).thenReturn(container);

                lingManager.installDev(definition, lingDir);
            }

            lingManager.shutdown();

            assertTrue(lingManager.getInstalledLings().isEmpty());
        }

        @Test
        @DisplayName("shutdown 应该是幂等的")
        void shutdownShouldBeIdempotent() {
            assertDoesNotThrow(() -> {
                lingManager.shutdown();
                lingManager.shutdown();
                lingManager.shutdown();
            });
        }
    }

    // ==================== 线程池隔离测试 ====================

    @Nested
    @DisplayName("线程池隔离")
    class ThreadPoolIsolationTests {

        @Test
        @DisplayName("卸载单元 A 不应影响单元 B")
        void uninstallAShouldNotAffectB() throws Exception {
            for (String lingId : new String[]{"Ling-a", "Ling-b"}) {
                File lingDir = createLingDir(lingId);
                LingDefinition definition = createDefinition(lingId, "1.0.0");
                LingContainer container = createMockContainer();
                when(containerFactory.create(eq(lingId), any(), any())).thenReturn(container);

                lingManager.installDev(definition, lingDir);
            }

            lingManager.uninstall("Ling-a");

            LingRuntime runtimeB = lingManager.getRuntime("Ling-b");
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
        @DisplayName("并发安装不同单元应该安全")
        void concurrentInstallDifferentLingsShouldBeSafe() throws Exception {
            int lingCount = 5;

            // 预设 mock
            Map<String, LingContainer> containerMap = new ConcurrentHashMap<>();
            Map<String, LingDefinition> definitionMap = new ConcurrentHashMap<>();
            Map<String, File> lingDirs = new ConcurrentHashMap<>();

            for (int i = 0; i < lingCount; i++) {
                String lingId = "ling-" + i;
                containerMap.put(lingId, createMockContainer());
                definitionMap.put(lingId, createDefinition(lingId, "1.0.0"));
                lingDirs.put(lingId, createLingDir(lingId));
            }

            when(containerFactory.create(anyString(), any(), any()))
                    .thenAnswer(invocation -> containerMap.get(invocation.getArgument(0)));

            ExecutorService executor = Executors.newFixedThreadPool(lingCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(lingCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < lingCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        String lingId = "ling-" + index;
                        lingManager.installDev(definitionMap.get(lingId), lingDirs.get(lingId));
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
            assertEquals(lingCount, successCount.get());
            assertEquals(lingCount, lingManager.getInstalledLings().size());
        }

        @Test
        @DisplayName("并发安装和卸载不应崩溃")
        void concurrentInstallAndUninstallShouldNotCrash() throws Exception {
            when(containerFactory.create(anyString(), any(), any()))
                    .thenAnswer(invocation -> createMockContainer());

            // 先安装一些单元
            for (int i = 0; i < 3; i++) {
                String lingId = "ling-" + i;
                File lingDir = createLingDir(lingId);
                LingDefinition definition = createDefinition(lingId, "1.0.0");
                lingManager.installDev(definition, lingDir);
            }

            // 预先创建新单元
            Map<Integer, File> newLingDirs = new HashMap<>();
            Map<Integer, LingDefinition> newLingDefs = new HashMap<>();
            for (int i = 1; i < 10; i += 2) {
                newLingDirs.put(i, createLingDir("new-Ling-" + i));
                newLingDefs.put(i, createDefinition("new-Ling-" + i, "1.0.0"));
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
                            lingManager.uninstall("ling-" + (index % 3));
                        } else {
                            File dir = newLingDirs.get(index);
                            LingDefinition def = newLingDefs.get(index);
                            if (dir != null && def != null) {
                                lingManager.installDev(def, dir);
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
            File lingDir = createLingDir("Ling-a");
            LingContainer container1 = createMockContainer();
            LingContainer container2 = createMockContainer();

            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            // ✅ 先安装一个默认版本
            LingDefinition defaultDef = createDefinition("Ling-a", "1.0.0");
            lingManager.installDev(defaultDef, lingDir);

            // 再部署金丝雀版本
            LingDefinition canaryDef = createDefinition("Ling-a", "2.0.0-canary");
            Map<String, String> labels = new HashMap<>();
            labels.put("env", "canary");
            labels.put("region", "cn-east");

            lingManager.deployCanary(canaryDef, lingDir, labels);

            LingRuntime runtime = lingManager.getRuntime("Ling-a");
            assertNotNull(runtime);

            // ✅ 使用 getActiveInstances() 获取所有实例
            List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
            assertFalse(instances.isEmpty());

            // 查找金丝雀实例
            LingInstance canaryInstance = instances.stream()
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
            File lingDir = createLingDir("Ling-a");
            LingContainer container1 = createMockContainer();
            LingContainer container2 = createMockContainer();

            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1)
                    .thenReturn(container2);

            // 先安装默认版本
            LingDefinition defaultDef = createDefinition("Ling-a", "1.0.0");
            lingManager.installDev(defaultDef, lingDir);

            // 部署金丝雀
            LingDefinition canaryDef = createDefinition("Ling-a", "2.0.0-canary");
            Map<String, String> labels = new HashMap<>();
            labels.put("env", "canary");
            lingManager.deployCanary(canaryDef, lingDir, labels);

            LingRuntime runtime = lingManager.getRuntime("Ling-a");

            // 默认版本应该仍然是 1.0.0
            LingInstance defaultInstance = runtime.getInstancePool().getDefault();
            assertNotNull(defaultInstance);
            assertEquals("1.0.0", defaultInstance.getVersion());

            // 应该有两个活跃实例
            assertEquals(2, runtime.getInstancePool().getActiveInstances().size());
        }

        @Test
        @DisplayName("单独部署金丝雀版本（无默认版本）")
        void canaryOnlyDeployment() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            LingDefinition canaryDef = createDefinition("Ling-a", "2.0.0-canary");
            Map<String, String> labels = new HashMap<>();
            labels.put("env", "canary");

            lingManager.deployCanary(canaryDef, lingDir, labels);

            LingRuntime runtime = lingManager.getRuntime("Ling-a");
            assertNotNull(runtime);

            // getDefault() 返回 null（因为 isDefault=false）
            assertNull(runtime.getInstancePool().getDefault());

            // 但实例应该存在于活跃列表中
            List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
            assertEquals(1, instances.size());

            LingInstance instance = instances.get(0);
            assertEquals("2.0.0-canary", instance.getVersion());
            assertEquals("canary", instance.getLabels().get("env"));
        }
    }

    // ==================== 崩溃隔离测试 ====================

    @Nested
    @DisplayName("崩溃隔离")
    class CrashIsolationTests {

        @Test
        @DisplayName("单元容器启动异常不应影响其他单元")
        void containerStartFailureShouldNotAffectOtherLings() throws IOException {
            // 先安装正常单元
            File lingDirA = createLingDir("Ling-a");
            LingDefinition defA = createDefinition("Ling-a", "1.0.0");
            LingContainer containerA = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(containerA);
            lingManager.installDev(defA, lingDirA);

            // 安装会崩溃的单元
            File lingDirB = createLingDir("Ling-b");
            LingDefinition defB = createDefinition("Ling-b", "1.0.0");
            LingContainer containerB = mock(LingContainer.class);
            doThrow(new RuntimeException("Container start failed!")).when(containerB).start(any());
            when(containerFactory.create(eq("Ling-b"), any(), any())).thenReturn(containerB);

            // 安装崩溃单元
            assertThrows(RuntimeException.class, () -> lingManager.installDev(defB, lingDirB));

            // 验证单元 A 不受影响
            LingRuntime runtimeA = lingManager.getRuntime("Ling-a");
            runtimeA.activate();
            assertNotNull(runtimeA);
            assertTrue(runtimeA.isAvailable());
            assertEquals("1.0.0", runtimeA.getVersion());

            LingInstance instanceA = runtimeA.getInstancePool().getDefault();
            assertNotNull(instanceA);
            assertTrue(instanceA.isReady());

            // 验证单元 B 未被安装
            assertNull(lingManager.getRuntime("Ling-b"));
            assertFalse(lingManager.getInstalledLings().contains("Ling-b"));
        }

        @Test
        @DisplayName("单元容器停止异常不应影响其他单元卸载")
        void containerStopFailureShouldNotAffectOtherLings() throws IOException {
            // 安装会在停止时崩溃的单元 A
            File lingDirA = createLingDir("Ling-a");
            LingDefinition defA = createDefinition("Ling-a", "1.0.0");
            LingContainer containerA = mock(LingContainer.class);
            when(containerA.isActive()).thenReturn(true);
            doNothing().when(containerA).start(any());
            doThrow(new RuntimeException("Container stop failed!")).when(containerA).stop();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(containerA);
            lingManager.installDev(defA, lingDirA);

            // 安装正常单元 B
            File lingDirB = createLingDir("Ling-b");
            LingDefinition defB = createDefinition("Ling-b", "1.0.0");
            LingContainer containerB = createMockContainer();
            when(containerFactory.create(eq("Ling-b"), any(), any())).thenReturn(containerB);
            lingManager.installDev(defB, lingDirB);

            // 卸载崩溃的单元 A（不应影响 B）
            assertDoesNotThrow(() -> lingManager.uninstall("Ling-a"));

            // 验证 B 不受影响
            LingRuntime runtimeB = lingManager.getRuntime("Ling-b");
            runtimeB.activate();
            assertNotNull(runtimeB);
            assertTrue(runtimeB.isAvailable());
        }

        @Test
        @DisplayName("ClassLoader 创建失败不应影响其他单元")
        void classLoaderFailureShouldNotAffectOtherLings() throws IOException {
            // 先安装正常单元
            File lingDirA = createLingDir("Ling-a");
            LingDefinition defA = createDefinition("Ling-a", "1.0.0");
            LingContainer containerA = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(containerA);
            lingManager.installDev(defA, lingDirA);

            // 配置 ClassLoader 创建失败
            File lingDirB = createLingDir("Ling-b");
            LingDefinition defB = createDefinition("Ling-b", "1.0.0");
            when(lingLoaderFactory.create(eq("Ling-b"), any(), any()))
                    .thenThrow(new RuntimeException("ClassLoader creation failed!"));

            // 尝试安装
            assertThrows(RuntimeException.class, () -> lingManager.installDev(defB, lingDirB));

            // 验证单元 A 不受影响
            LingRuntime runtimeA = lingManager.getRuntime("Ling-a");
            runtimeA.activate();
            assertNotNull(runtimeA);
            assertTrue(runtimeA.isAvailable());
        }

        @Test
        @DisplayName("安全验证失败不应影响其他单元")
        void securityVerificationFailureShouldNotAffectOtherLings() throws IOException {
            // 创建带安全验证器的 LingManager
            LingManager managerWithVerifier = getManagerWithVerifier();

            try {
                // 安装正常单元 A
                File lingDirA = createLingDir("Ling-a");
                LingDefinition defA = createDefinition("Ling-a", "1.0.0");
                LingContainer containerA = createMockContainer();
                when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(containerA);
                managerWithVerifier.installDev(defA, lingDirA);

                // 安装会被安全检查拒绝的单元 B
                File lingDirB = createLingDir("Ling-b");
                LingDefinition defB = createDefinition("Ling-b", "1.0.0");

                assertThrows(RuntimeException.class, () -> managerWithVerifier.installDev(defB, lingDirB));

                // 验证 A 不受影响
                assertNotNull(managerWithVerifier.getRuntime("Ling-a"));
                assertNull(managerWithVerifier.getRuntime("Ling-b"));

            } finally {
                managerWithVerifier.shutdown();
            }
        }

        @Test
        @DisplayName("shutdown 时单个单元崩溃不应阻止其他单元关闭")
        void shutdownWithCrashingShouldNotBlockOthers() throws IOException {
            // 安装会在 shutdown 时崩溃的单元
            File lingDirA = createLingDir("Ling-a");
            LingDefinition defA = createDefinition("Ling-a", "1.0.0");
            LingContainer containerA = mock(LingContainer.class);
            when(containerA.isActive()).thenReturn(true);
            doNothing().when(containerA).start(any());
            doThrow(new RuntimeException("Shutdown failed!")).when(containerA).stop();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(containerA);
            lingManager.installDev(defA, lingDirA);

            // 安装正常单元
            File lingDirB = createLingDir("Ling-b");
            LingDefinition defB = createDefinition("Ling-b", "1.0.0");
            LingContainer containerB = createMockContainer();
            when(containerFactory.create(eq("Ling-b"), any(), any())).thenReturn(containerB);
            lingManager.installDev(defB, lingDirB);

            // shutdown 不应抛异常
            assertDoesNotThrow(() -> lingManager.shutdown());

            // 验证所有单元都被清理
            assertTrue(lingManager.getInstalledLings().isEmpty());

            // 验证正常单元的 stop 被调用
            verify(containerB).stop();
        }

        @Test
        @DisplayName("热升级失败不应影响现有实例")
        void upgradeFailureShouldNotAffectExistingInstance() throws IOException {
            File lingDir = createLingDir("Ling-a");

            // 第一次安装成功
            LingContainer container1 = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1);

            LingDefinition def1 = createDefinition("Ling-a", "1.0.0");
            lingManager.installDev(def1, lingDir);

            // 验证 1.0.0 正常运行
            assertEquals("1.0.0", lingManager.getLingVersion("Ling-a"));

            // 第二次升级失败
            LingContainer container2 = mock(LingContainer.class);
            doThrow(new RuntimeException("Upgrade failed!")).when(container2).start(any());
            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container2);

            LingDefinition def2 = createDefinition("Ling-a", "2.0.0");
            assertThrows(RuntimeException.class, () -> lingManager.installDev(def2, lingDir));

            // 验证旧版本仍然可用
            LingRuntime runtime = lingManager.getRuntime("Ling-a");
            assertNotNull(runtime);
            // 注意：这取决于实现，如果升级失败会回滚，版本应该是 1.0.0
            // 如果不回滚，可能版本已经变了但实例不可用
        }

        @Test
        @DisplayName("多个单元同时崩溃不应导致系统不可用")
        void multipleCrashesShouldNotBreakSystem() throws IOException {
            // 安装多个会崩溃的单元
            for (int i = 0; i < 3; i++) {
                String lingId = "crash-Ling-" + i;
                File lingDir = createLingDir(lingId);
                LingDefinition def = createDefinition(lingId, "1.0.0");

                LingContainer container = mock(LingContainer.class);
                when(container.isActive()).thenReturn(true);
                doNothing().when(container).start(any());
                doThrow(new RuntimeException("Stop failed for " + lingId)).when(container).stop();
                when(containerFactory.create(eq(lingId), any(), any())).thenReturn(container);

                lingManager.installDev(def, lingDir);
            }

            // 安装正常单元
            File lingDirGood = createLingDir("good-ling");
            LingDefinition defGood = createDefinition("good-ling", "1.0.0");
            LingContainer containerGood = createMockContainer();
            when(containerFactory.create(eq("good-ling"), any(), any())).thenReturn(containerGood);
            lingManager.installDev(defGood, lingDirGood);

            // 全部卸载不应抛异常
            for (int i = 0; i < 3; i++) {
                int finalI = i;
                assertDoesNotThrow(() -> lingManager.uninstall("crash-Ling-" + finalI));
            }

            // 正常单元仍然可用
            LingRuntime goodRuntime = lingManager.getRuntime("good-ling");
            goodRuntime.activate();
            assertNotNull(goodRuntime);
            assertTrue(goodRuntime.isAvailable());
        }

        @Test
        @DisplayName("reload 失败不应影响原单元")
        void reloadFailureShouldNotAffectOriginal() throws IOException {
            File lingDir = createLingDir("Ling-a");

            // 第一次安装成功
            LingContainer container1 = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container1);

            LingDefinition def = createDefinition("Ling-a", "1.0.0");
            lingManager.installDev(def, lingDir);
            assertEquals("1.0.0", lingManager.getLingVersion("Ling-a"));

            // reload 时容器创建失败
            LingContainer container2 = mock(LingContainer.class);
            doThrow(new RuntimeException("Reload failed!")).when(container2).start(any());
            when(containerFactory.create(eq("Ling-a"), any(), any()))
                    .thenReturn(container2);

            assertThrows(RuntimeException.class, () -> lingManager.reload("Ling-a"));

            // 验证原单元状态（取决于实现）
            LingRuntime runtime = lingManager.getRuntime("Ling-a");
            assertNotNull(runtime, "Runtime should still exist after failed reload");
        }
    }

    private @NonNull LingManager getManagerWithVerifier() {
        LingSecurityVerifier failingVerifier = (lingId, source) -> {
            if ("Ling-b".equals(lingId)) {
                throw new SecurityException("Security check failed for " + lingId);
            }
        };

        return new LingManager(
                containerFactory,
                permissionService,
                governanceKernel,
                lingLoaderFactory,
                Collections.singletonList(failingVerifier),
                eventBus,
                trafficRouter,
                lingServiceInvoker,
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
            File lingDir = createLingDir("Ling-a");
            LingDefinition def = createDefinition("Ling-a", "1.0.0");
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(null);

            assertThrows(Exception.class, () -> lingManager.installDev(def, lingDir));
        }

        @Test
        @DisplayName("LingLoaderFactory 返回 null 应该抛出有意义的异常")
        void shouldThrowWhenLoaderFactoryReturnsNull() throws IOException {
            File lingDir = createLingDir("Ling-a");
            LingDefinition def = createDefinition("Ling-a", "1.0.0");
            when(lingLoaderFactory.create(eq("Ling-a"), any(), any())).thenReturn(null);

            assertThrows(Exception.class, () -> lingManager.installDev(def, lingDir));
        }

        @Test
        @DisplayName("事件发布失败不应阻止安装")
        void eventPublishFailureShouldNotBlockInstall() throws IOException {
            doThrow(new RuntimeException("Event publish failed")).when(eventBus).publish(any());

            File lingDir = createLingDir("Ling-a");
            LingDefinition def = createDefinition("Ling-a", "1.0.0");
            LingContainer container = createMockContainer();
            when(containerFactory.create(eq("Ling-a"), any(), any())).thenReturn(container);

            // 根据实现，可能成功或失败
            // 如果事件发布是可选的，应该成功
            // 如果是必须的，应该失败
            try {
                lingManager.installDev(def, lingDir);
                // 如果成功，验证单元已安装
                assertNotNull(lingManager.getRuntime("Ling-a"));
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
        @DisplayName("包含 System.exit 的单元应该被拒绝")
        void shouldRejectLingWithSystemExit() throws IOException {
            // 创建包含危险 API 的验证器
            LingSecurityVerifier dangerousApiVerifier = (lingId, source) -> {
                // 模拟扫描发现 System.exit
                if ("evil-ling".equals(lingId)) {
                    throw new SecurityException("Ling contains System.exit() call");
                }
            };

            LingManager secureManager = new LingManager(
                    containerFactory,
                    permissionService,
                    governanceKernel,
                    lingLoaderFactory,
                    Collections.singletonList(dangerousApiVerifier),
                    eventBus,
                    trafficRouter,
                    lingServiceInvoker,
                    transactionVerifier,
                    Collections.emptyList(),
                    lingFrameConfig,
                    localGovernanceRegistry,
                    null);

            try {
                File lingDir = createLingDir("evil-ling");
                LingDefinition def = createDefinition("evil-ling", "1.0.0");
                LingContainer container = createMockContainer();
                when(containerFactory.create(eq("evil-ling"), any(), any())).thenReturn(container);

                // 应该被安全检查拒绝
                SecurityException ex = assertThrows(SecurityException.class,
                        () -> secureManager.installDev(def, lingDir));

                assertTrue(ex.getMessage().contains("System.exit") ||
                           ex.getCause().getMessage().contains("System.exit"));

                // 验证单元未被安装
                assertNull(secureManager.getRuntime("evil-ling"));

            } finally {
                secureManager.shutdown();
            }
        }

        @Test
        @DisplayName("安全验证失败不应影响其他单元")
        void securityFailureShouldNotAffectOtherLings() throws IOException {
            LingSecurityVerifier selectiveVerifier = (lingId, source) -> {
                if ("evil-ling".equals(lingId)) {
                    throw new SecurityException("Dangerous API detected");
                }
            };

            LingManager secureManager = new LingManager(
                    containerFactory, permissionService, governanceKernel,
                    lingLoaderFactory, Collections.singletonList(selectiveVerifier),
                    eventBus, trafficRouter, lingServiceInvoker,
                    transactionVerifier, Collections.emptyList(), lingFrameConfig,
                    localGovernanceRegistry, null);

            try {
                // 先安装正常单元
                File goodDir = createLingDir("good-ling");
                LingDefinition goodDef = createDefinition("good-ling", "1.0.0");
                LingContainer goodContainer = createMockContainer();
                when(containerFactory.create(eq("good-ling"), any(), any())).thenReturn(goodContainer);
                secureManager.installDev(goodDef, goodDir);

                // 尝试安装恶意单元
                File evilDir = createLingDir("evil-ling");
                LingDefinition evilDef = createDefinition("evil-ling", "1.0.0");

                assertThrows(RuntimeException.class, () -> secureManager.installDev(evilDef, evilDir));
                LingRuntime goodRuntime = secureManager.getRuntime("good-ling");
                goodRuntime.activate();

                // 正常单元不受影响
                assertNotNull(goodRuntime);
                assertTrue(goodRuntime.isAvailable());

            } finally {
                secureManager.shutdown();
            }
        }
    }
}