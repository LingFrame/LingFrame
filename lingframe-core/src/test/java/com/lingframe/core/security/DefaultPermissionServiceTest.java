package com.lingframe.core.security;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.config.LingFrameConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultPermissionServiceTest {

    private DefaultPermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new DefaultPermissionService();
    }

    @AfterEach
    void tearDown() {
        // 重置开发模式设置
        LingFrameConfig.setDevMode(false);
    }

    @Test
    void testIsAllowedWithGlobalWhitelist() {
        // 测试全局白名单
        assertTrue(permissionService.isAllowed("any-plugin", "com.lingframe.api.SomeApi", AccessType.READ));
    }

    @Test
    void testGrantAndCheckPermission() {
        String pluginId = "test-plugin";
        String capability = "test-capability";
        AccessType accessType = AccessType.WRITE;

        // 初始时应该没有权限
        assertFalse(permissionService.isAllowed(pluginId, capability, accessType));

        // 授予权限
        permissionService.grant(pluginId, capability, accessType);

        // 现在应该有权限了
        assertTrue(permissionService.isAllowed(pluginId, capability, accessType));

        // READ权限应该被WRITE权限覆盖
        assertTrue(permissionService.isAllowed(pluginId, capability, AccessType.READ));
    }

    @Test
    void testDevModeFallback() {
        String pluginId = "test-plugin";
        String capability = "test-capability";
        AccessType accessType = AccessType.WRITE;

        // 开发模式下，即使没有权限也应该返回true
        LingFrameConfig.setDevMode(true);
        assertTrue(permissionService.isAllowed(pluginId, capability, accessType));
    }

    @Test
    void testGetPermission() {
        String pluginId = "test-plugin";
        String capability = "test-capability";
        AccessType accessType = AccessType.WRITE;

        // 初始时应该返回null
        assertNull(permissionService.getPermission(pluginId, capability));

        // 授予权限
        permissionService.grant(pluginId, capability, accessType);

        // 现在应该能获取到权限
        assertNotNull(permissionService.getPermission(pluginId, capability));
    }
}