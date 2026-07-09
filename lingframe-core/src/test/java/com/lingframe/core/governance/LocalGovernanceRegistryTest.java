package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.event.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LocalGovernanceRegistry 测试")
class LocalGovernanceRegistryTest {

    @TempDir
    Path tempDir;

    @Mock
    private EventBus eventBus;

    private LocalGovernanceRegistry registry;
    private File configFile;

    @BeforeEach
    void setUp() throws Exception {
        // 使用临时文件路径构造注册表，避免污染真实环境。
        configFile = tempDir.resolve("ling-governance-patch.yml").toFile();
        registry = new LocalGovernanceRegistry(eventBus, configFile.getAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        if (configFile.exists()) {
            configFile.delete();
        }
    }

    @Nested
    @DisplayName("补丁管理")
    class PatchManagementTests {

        @Test
        @DisplayName("更新后应能按灵元标识取回补丁")
        void testUpdateAndGetPatch() {
            GovernancePolicy policy = new GovernancePolicy();
            policy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                    .permissionId("perm-1")
                    .methodPattern("com.example.*")
                    .build());

            registry.updatePatch("Ling-1", policy);

            GovernancePolicy retrieved = registry.getPatch("Ling-1");
            assertNotNull(retrieved);
            assertFalse(retrieved.getPermissions().isEmpty());
            assertEquals("perm-1", retrieved.getPermissions().get(0).getPermissionId());
            assertTrue(configFile.exists(), "配置文件应被创建");
        }
    }

    @Nested
    @DisplayName("持久化加载")
    class PersistenceTests {

        @Test
        @DisplayName("重新构造注册表后应能从文件加载补丁")
        void testLoadFromFile() throws Exception {
            GovernancePolicy policy = new GovernancePolicy();
            policy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                    .permissionId("perm-2")
                    .build());
            policy.getInvocation().setTimeoutMs(1500);
            policy.getInvocation().setRateLimitPerSecond(9);
            registry.updatePatch("Ling-2", policy);

            LocalGovernanceRegistry newRegistry = new LocalGovernanceRegistry(eventBus, configFile.getAbsolutePath());

            GovernancePolicy loadedPolicy = newRegistry.getPatch("Ling-2");
            assertNotNull(loadedPolicy);
            assertFalse(loadedPolicy.getPermissions().isEmpty());
            assertEquals("perm-2", loadedPolicy.getPermissions().get(0).getPermissionId());
            assertEquals(Integer.valueOf(1500), loadedPolicy.getInvocation().getTimeoutMs());
            assertEquals(Integer.valueOf(9), loadedPolicy.getInvocation().getRateLimitPerSecond());
        }
    }

    @Nested
    @DisplayName("临时文件清理测试（P2.2）")
    class TmpFileCleanupTests {

        @Test
        @DisplayName("正常 save 成功后 tmp 文件应不存在")
        void shouldNotLeaveTmpFileAfterSuccessfulSave() {
            GovernancePolicy policy = new GovernancePolicy();
            registry.updatePatch("Ling-1", policy);

            File tmpFile = new File(configFile.getAbsolutePath() + ".tmp");
            assertFalse(tmpFile.exists(), "正常 save 成功后 tmp 文件应已被原子 move 走");
            assertTrue(configFile.exists(), "目标文件应存在");
        }

        @Test
        @DisplayName("move 失败时应清理残留 tmp 文件，避免磁盘泄漏")
        void shouldCleanUpTmpFileWhenMoveFails() throws Exception {
            // 构造让 Files.move 必然失败的场景：storePath 指向一个已存在的非空目录。
            // move(文件, 非空目录, REPLACE_EXISTING) 在所有 OS 上都会抛 IOException。
            Path storePath = tempDir.resolve("governance-as-dir");
            Files.createDirectories(storePath);
            Files.createFile(storePath.resolve("placeholder")); // 让目录非空

            LocalGovernanceRegistry r = new LocalGovernanceRegistry(eventBus, storePath.toString());

            GovernancePolicy policy = new GovernancePolicy();
            // updatePatch 内部 save() 会写 tmp 成功，但 move 失败 → 进入 catch 清理 tmp
            r.updatePatch("Ling-x", policy);

            Path tmpFile = tempDir.resolve("governance-as-dir.tmp");
            assertFalse(Files.exists(tmpFile),
                    "move 失败后残留 tmp 文件应被 deleteIfExists 清理");
        }

        @Test
        @DisplayName("多次 updatePatch 不应累积 tmp 文件")
        void shouldNotAccumulateTmpFilesAcrossMultipleUpdates() {
            for (int i = 0; i < 5; i++) {
                GovernancePolicy policy = new GovernancePolicy();
                policy.getInvocation().setTimeoutMs(1000 + i);
                registry.updatePatch("Ling-" + i, policy);
            }

            File tmpFile = new File(configFile.getAbsolutePath() + ".tmp");
            assertFalse(tmpFile.exists(), "多次 updatePatch 不应留下累积的 tmp 文件");
            assertTrue(configFile.exists());
        }
    }
}
