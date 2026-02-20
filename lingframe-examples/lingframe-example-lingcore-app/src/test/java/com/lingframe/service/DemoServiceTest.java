package com.lingframe.service;

import com.lingframe.api.context.LingContext;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.ling.LingManager;
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
    private LingManager lingManager;

    @Autowired
    private LingContext lingContext;

    static {
        // 确保单元已加载
        LingFrameConfig.current().setDevMode(true);
    }

    @BeforeEach
    public void setUp() {
        log.info("Installed Lings: {}", lingManager.getInstalledLings());
    }

    @Test
    public void testCallUserService() {
        // 测试查询用户
        Object result = lingContext.invoke("user-ling:query_user", 1).orElse(null);
        log.info("Query result: {}", result);
        assertNotNull(result);

        // 测试列出用户
        result = lingContext.invoke("user-ling:list_users").orElse(null);
        log.info("List result: {}", result);
        assertNotNull(result);

        // 测试创建用户
        result = lingContext.invoke("user-ling:create_user", "Test User", "test@example.com").orElse(null);
        log.info("Create result: {}", result);
        assertNotNull(result);
    }
}