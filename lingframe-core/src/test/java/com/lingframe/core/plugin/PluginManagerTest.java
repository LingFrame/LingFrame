package com.lingframe.core.plugin;

import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.PluginContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        // 创建一个简单的ContainerFactory实现用于测试
        ContainerFactory containerFactory = new ContainerFactory() {
            @Override
            public PluginContainer create(String pluginId, File jarFile, ClassLoader classLoader) {
                return null; // 在这个测试中不需要真实的容器
            }
        };

        GovernanceKernel governanceKernel = new GovernanceKernel(null);

        pluginManager = new PluginManager(containerFactory, governanceKernel);
    }

    @Test
    void testGetInstalledPlugins() {
        // 初始时应该没有插件
        Set<String> installedPlugins = pluginManager.getInstalledPlugins();
        assertNotNull(installedPlugins);
        assertTrue(installedPlugins.isEmpty());
    }

    @Test
    void testGetPluginVersion() {
        // 测试不存在的插件版本
        String version = pluginManager.getPluginVersion("non-existent-plugin");
        assertNull(version);
    }

    @Test
    void testShutdown() {
        // shutdown方法不应该抛出异常
        assertDoesNotThrow(() -> pluginManager.shutdown());
    }
}