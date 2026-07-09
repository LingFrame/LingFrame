package com.lingframe.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link SpringWebHostSupport} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 现有 SpringWebHostSupportTest 已覆盖 registerMapping / invokeTarget / unregisterMappings，
 * 此处补充 isInitialized / setApplicationContext / clearSpringDocCache 路径。
 */
@DisplayName("SpringWebHostSupport 补充测试")
class SpringWebHostSupportSupplementTest {

    @Test
    @DisplayName("init 前应未初始化")
    void shouldNotBeInitializedBeforeInit() {
        SpringWebHostSupport support = new SpringWebHostSupport();

        assertFalse(support.isInitialized());
    }

    @Test
    @DisplayName("init 后应已初始化")
    void shouldBeInitializedAfterInit() {
        SpringWebHostSupport support = new SpringWebHostSupport();
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        RequestMappingHandlerAdapter adapter = mock(RequestMappingHandlerAdapter.class);
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            support.init(mapping, adapter, context);

            assertTrue(support.isInitialized());
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("setApplicationContext 应为安全空操作")
    void shouldSafeCallSetApplicationContext() {
        SpringWebHostSupport support = new SpringWebHostSupport();
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            // 该方法用于测试环境手动注入，实际为空实现，不应抛异常
            assertDoesNotThrow(() -> support.setApplicationContext(context));
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("clearSpringDocCache 在未初始化时应安全返回")
    void shouldSafeReturnWhenClearSpringDocCacheBeforeInit() {
        SpringWebHostSupport support = new SpringWebHostSupport();

        // hostContext 为 null，应直接返回不抛异常
        assertDoesNotThrow(support::clearSpringDocCache);
    }

    @Test
    @DisplayName("clearSpringDocCache 在无 springdoc Bean 时应安全执行")
    void shouldSafelyClearSpringDocCacheWithoutSpringDocBeans() {
        SpringWebHostSupport support = new SpringWebHostSupport();
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        RequestMappingHandlerAdapter adapter = mock(RequestMappingHandlerAdapter.class);
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        try {
            support.init(mapping, adapter, context);

            // GenericApplicationContext 默认无 springdoc 相关 Bean，应安全执行
            assertDoesNotThrow(support::clearSpringDocCache);
        } finally {
            context.close();
        }
    }
}
