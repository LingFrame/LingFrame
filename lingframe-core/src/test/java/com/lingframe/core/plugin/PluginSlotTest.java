package com.lingframe.core.plugin;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.security.DefaultPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class PluginSlotTest {

    private PluginSlot pluginSlot;
    private PermissionService permissionService;
    private ScheduledExecutorService scheduledExecutorService;

    @BeforeEach
    void setUp() {
        String pluginId = "test-plugin";
        permissionService = new DefaultPermissionService();
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        pluginSlot = new PluginSlot(pluginId, scheduledExecutorService, permissionService);
    }

    @Test
    void testGetVersion() {
        // 初始版本应该为null
        assertNull(pluginSlot.getVersion());
    }

    @Test
    void testUninstall() {
        // uninstall方法不应该抛出异常
        assertDoesNotThrow(() -> pluginSlot.uninstall());
    }
}