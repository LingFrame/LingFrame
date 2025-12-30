package com.lingframe.service;

import com.lingframe.api.context.PluginContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.plugin.PluginManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
@SpringBootTest
public class DemoServiceTest {

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private PluginContext pluginContext;

    static {
        // 确保插件已加载
        LingFrameConfig.setDevMode(true);
    }

    @BeforeEach
    public void setUp() {
        log.info("Installed plugins: {}", pluginManager.getInstalledPlugins());
    }

    @Test
    public void testCallUserService() {
        // 测试查询用户
        Object result = pluginContext.invoke("user-plugin:query_user", 1).orElse(null);
        log.info("Query result: {}", result);
        assertNotNull(result);

        // 测试列出用户
        result = pluginContext.invoke("user-plugin:list_users").orElse(null);
        log.info("List result: {}", result);
        assertNotNull(result);

        // 测试创建用户
        result = pluginContext.invoke("user-plugin:create_user", "Test User", "test@example.com").orElse(null);
        log.info("Create result: {}", result);
        assertNotNull(result);
    }
}