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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LocalGovernanceRegistry 单元测试")
class LocalGovernanceRegistryTest {

    @TempDir
    Path tempDir;

    @Mock
    private EventBus eventBus;

    private LocalGovernanceRegistry registry;
    private File configFile;

    @BeforeEach
    void setUp() throws Exception {
        // 使用临时文件路径构造 Registry
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
        @DisplayName("更新并获取补丁应成功")
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

            // 验证文件持久化
            assertTrue(configFile.exists(), "配置文件应该被创建");
        }
    }

    @Nested
    @DisplayName("持久化加载")
    class PersistenceTests {

        @Test
        @DisplayName("重启后应能从文件加载补丁")
        void testLoadFromFile() throws Exception {
            // 先保存一个补丁
            GovernancePolicy policy = new GovernancePolicy();
            policy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                    .permissionId("perm-2")
                    .build());
            registry.updatePatch("Ling-2", policy);

            // 创建新的 Registry 实例模拟重启
            LocalGovernanceRegistry newRegistry = new LocalGovernanceRegistry(eventBus, configFile.getAbsolutePath());

            // 构造函数中已经调用了 load()，所以这里不需要再反射调 load()
            // 但为了模拟"重启"后读取，我们直接断言即可
            // 如果构造函数中的load()已经读取了，那么getPatch应该能拿到数据

            GovernancePolicy loadedPolicy = newRegistry.getPatch("Ling-2");
            assertNotNull(loadedPolicy);
            assertFalse(loadedPolicy.getPermissions().isEmpty());
            assertEquals("perm-2", loadedPolicy.getPermissions().get(0).getPermissionId());
        }
    }

    // setPrivateField removed as it is no longer needed
}
