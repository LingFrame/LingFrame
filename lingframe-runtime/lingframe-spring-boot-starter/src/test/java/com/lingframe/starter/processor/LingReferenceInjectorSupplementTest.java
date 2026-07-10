package com.lingframe.starter.processor;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/**
 * {@link LingReferenceInjector} 补充测试。
 * <p>
 * 🔥 边界收敛：本测试只覆盖「按注解字段类型 + 路由锚点取代理」注入流程，
 * 删除原 fallback 代理相关用例——降级语义归 ResilienceGovernanceFilter + YAML references。
 * 新增：灵核级 BPP 二次扫（AfterInitialization 阶段兜底注入）验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LingReferenceInjector 补充测试")
class LingReferenceInjectorSupplementTest {

    /** 测试用服务接口 */
    interface TestService {
        String hello(String name);
    }

    /** 携带默认锚点 @LingReference 字段的测试 Bean（单字段，避免 strict 桩交叉） */
    static class DefaultAnchorBean {
        @LingReference
        TestService service;
    }

    /** 携带显式锚点 @LingReference 字段的测试 Bean */
    static class ExplicitAnchorBean {
        @LingReference(lingId = "user-ling", serviceId = "authService")
        TestService anchoredService;
    }

    @Mock
    private LingContext lingContext;

    @Test
    @DisplayName("LingContext 未就绪时 postProcessBeforeInitialization 应跳过注入")
    void shouldSkipWhenLingContextNotReady() {
        // 单参构造，applicationContext 未设置 → getLingContext() 返回 null
        LingReferenceInjector injector = new LingReferenceInjector("ling-a");

        DefaultAnchorBean bean = new DefaultAnchorBean();

        Object result = injector.postProcessBeforeInitialization(bean, "targetBean");

        assertSame(bean, result);
        assertNull(bean.service);
    }

    @Test
    @DisplayName("postProcessBeforeInitialization 应注入默认锚点 @LingReference 字段")
    void shouldInjectDefaultAnchorField() {
        TestService mockService = org.mockito.Mockito.mock(TestService.class);
        when(lingContext.getService(TestService.class, "", "")).thenReturn(Optional.of(mockService));

        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);

        DefaultAnchorBean bean = new DefaultAnchorBean();
        injector.postProcessBeforeInitialization(bean, "targetBean");

        assertNotNull(bean.service);
        assertSame(mockService, bean.service);
    }

    @Test
    @DisplayName("带路由锚点（lingId/serviceId）应透到 getService(Class,lingId,serviceId)")
    void shouldPassAnchorToGetService() {
        TestService mockService = org.mockito.Mockito.mock(TestService.class);
        when(lingContext.getService(TestService.class, "user-ling", "authService"))
                .thenReturn(Optional.of(mockService));

        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);

        ExplicitAnchorBean bean = new ExplicitAnchorBean();
        injector.postProcessBeforeInitialization(bean, "targetBean");

        assertNotNull(bean.anchoredService);
        assertSame(mockService, bean.anchoredService);
    }

    @Test
    @DisplayName("字段已有值时应跳过注入")
    void shouldSkipWhenFieldAlreadyInjected() {
        TestService existing = org.mockito.Mockito.mock(TestService.class);
        when(lingContext.getService(TestService.class, "", "")).thenReturn(Optional.of(existing));

        LingReferenceInjector injector = new LingReferenceInjector("ling-a", lingContext);

        DefaultAnchorBean bean = new DefaultAnchorBean();
        // 预先设置字段值
        TestService preSet = org.mockito.Mockito.mock(TestService.class);
        bean.service = preSet;

        injector.postProcessBeforeInitialization(bean, "targetBean");

        // service 字段应保持预先设置的值
        assertSame(preSet, bean.service);
    }

    @Test
    @DisplayName("postProcessAfterInitialization 二次扫兜底注入未就绪字段")
    void shouldInjectInAfterInitWhenBeforeInitSkipped() {
        // BeforeInit 阶段：lingContext 未就绪（用单参构造，applicationContext 未设置）
        LingReferenceInjector injector = new LingReferenceInjector("ling-a");

        DefaultAnchorBean bean = new DefaultAnchorBean();
        Object resultBefore = injector.postProcessBeforeInitialization(bean, "targetBean");
        assertSame(bean, resultBefore);
        assertNull(bean.service);

        // AfterInit 阶段：lingContext 已就绪（通过新构造函数补 ctx）
        LingReferenceInjector armedInjector = new LingReferenceInjector("ling-a", lingContext);
        TestService mockService = org.mockito.Mockito.mock(TestService.class);
        when(lingContext.getService(TestService.class, "", "")).thenReturn(Optional.of(mockService));

        Object resultAfter = armedInjector.postProcessAfterInitialization(bean, "targetBean");
        assertSame(bean, resultAfter);
        assertNotNull(bean.service);
    }
}
