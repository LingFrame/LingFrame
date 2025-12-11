package com.lingframe.service;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.plugin.PluginManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@SpringBootTest
public class DemoServiceTest {

    @Autowired
    private DemoService demoService;

    @Autowired
    private PluginManager pluginManager;

    static {
        // 确保插件已加载
        LingFrameConfig.setDevMode(true);
    }

    @BeforeEach
    public void setUp() {
        System.out.println("Installed plugins: " + pluginManager.getInstalledPlugins());
    }

    @Test
    public void testGetPluginInfo() {
        String pluginInfo = demoService.getPluginInfo();
        System.out.println(pluginInfo);
        assertNotNull(pluginInfo);
        // 检查是否包含user-plugin
        assertTrue(pluginInfo.contains("user-plugin"));
    }

    @Test
    public void testCallUserService() {
        // 测试查询用户
        String result = demoService.callUserService("query", "1");
        System.out.println("Query result: " + result);
        assertNotNull(result);
        
        // 测试列出用户
        result = demoService.callUserService("list");
        System.out.println("List result: " + result);
        assertNotNull(result);
        
        // 测试创建用户
        result = demoService.callUserService("create", "Test User", "test@example.com");
        System.out.println("Create result: " + result);
        assertNotNull(result);
    }
}