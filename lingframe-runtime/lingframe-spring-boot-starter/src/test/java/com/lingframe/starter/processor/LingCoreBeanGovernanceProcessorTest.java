package com.lingframe.starter.processor;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("LingCoreBeanGovernanceProcessor 与 Interceptor 测试")
class LingCoreBeanGovernanceProcessorTest {

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

    @Service
    public static class TestService {
        @RequiresPermission("service:read")
        @Auditable(action = "read_service")
        public void doSomething() {}

        public void doObjectLike() {}

        public void createSomething() {} // 用于根据方法名推导审计
    }

    @Component
    public static class ReferenceBean {
        @LingReference
        private Object someRef;
    }

    public static class NonAnnotatedBean {}

    @Service
    public static final class FinalService {
        @RequiresPermission("final:read")
        public void test() {}
    }

    @Test
    @DisplayName("各种 Bean 的拦截与代理处理逻辑")
    void testBeanPostProcessor() {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        PermissionService permissionService = mock(PermissionService.class);
        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);
        LingFrameProperties properties = new LingFrameProperties();
        EntryInvocationGovernanceResolver resolver = mock(EntryInvocationGovernanceResolver.class);

        when(applicationContext.getBean(PermissionService.class)).thenReturn(permissionService);
        when(applicationContext.getBean(InvocationPipelineEngine.class)).thenReturn(pipelineEngine);
        when(applicationContext.getBean(LingFrameProperties.class)).thenReturn(properties);
        when(applicationContext.getBean(EntryInvocationGovernanceResolver.class)).thenReturn(resolver);

        LingCoreBeanGovernanceProcessor processor = new LingCoreBeanGovernanceProcessor();
        processor.setApplicationContext(applicationContext);

        TestService service = new TestService();

        // 场景 1：如果 context 还没准备好，直接返回原 bean
        ApplicationContext emptyCtx = mock(ApplicationContext.class);
        LingCoreBeanGovernanceProcessor emptyProcessor = new LingCoreBeanGovernanceProcessor();
        emptyProcessor.setApplicationContext(emptyCtx);
        assertSame(service, emptyProcessor.postProcessAfterInitialization(service, "testService"));

        // 场景 2：灵核治理未开启，直接返回原 bean
        properties.getLingCoreGovernance().setEnabled(false);
        assertSame(service, processor.postProcessAfterInitialization(service, "testService"));

        // 场景 3：正常拦截并生成代理
        properties.getLingCoreGovernance().setEnabled(true);
        Object proxy = processor.postProcessAfterInitialization(service, "testService");
        assertNotSame(service, proxy);
        assertTrue(AopUtils.isCglibProxy(proxy));

        // 场景 4：排除前缀的 bean，不生成代理
        assertSame(service, processor.postProcessAfterInitialization(service, "org.springframework.test"));

        // 场景 5：已经是 AOP 代理的 bean，不生成代理
        assertSame(proxy, processor.postProcessAfterInitialization(proxy, "proxyService"));

        // 场景 6：含有 @LingReference 的 bean，不生成代理
        ReferenceBean referenceBean = new ReferenceBean();
        assertSame(referenceBean, processor.postProcessAfterInitialization(referenceBean, "referenceBean"));

        // 场景 7：无相关治理注解的普通 bean，不生成代理
        NonAnnotatedBean nonAnnotatedBean = new NonAnnotatedBean();
        assertSame(nonAnnotatedBean, processor.postProcessAfterInitialization(nonAnnotatedBean, "nonAnnotated"));
        
        // 场景 8：postProcessBeforeInitialization 应该原样返回
        assertSame(service, processor.postProcessBeforeInitialization(service, "testService"));

        // 场景 9：测试 final 类 Bean，代理创建失败时走 catch 并返回原 bean
        FinalService finalService = new FinalService();
        assertSame(finalService, processor.postProcessAfterInitialization(finalService, "finalService"));

        // 场景 10：测试 beanName 为 null 时因 @NonNull 限制抛出 NullPointerException
        assertThrows(NullPointerException.class, () ->
            processor.postProcessAfterInitialization(service, null)
        );
    }

    @Test
    @DisplayName("代理执行与拦截器治理分支逻辑测试")
    void testInterceptorGovernance() throws Throwable {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        PermissionService permissionService = mock(PermissionService.class);
        InvocationPipelineEngine pipelineEngine = mock(InvocationPipelineEngine.class);
        LingFrameProperties properties = new LingFrameProperties();
        EntryInvocationGovernanceResolver resolver = mock(EntryInvocationGovernanceResolver.class);

        when(applicationContext.getBean(PermissionService.class)).thenReturn(permissionService);
        when(applicationContext.getBean(InvocationPipelineEngine.class)).thenReturn(pipelineEngine);
        when(applicationContext.getBean(LingFrameProperties.class)).thenReturn(properties);
        when(applicationContext.getBean(EntryInvocationGovernanceResolver.class)).thenReturn(resolver);

        LingCoreBeanGovernanceProcessor processor = new LingCoreBeanGovernanceProcessor();
        processor.setApplicationContext(applicationContext);

        TestService service = new TestService();
        properties.getLingCoreGovernance().setEnabled(true);
        TestService proxy = (TestService) processor.postProcessAfterInitialization(service, "testService");

        // 场景 1：无 caller 且 governInternalCalls 为 false（默认）
        properties.getLingCoreGovernance().setGovernInternalCalls(false);
        proxy.doSomething();
        verify(pipelineEngine, never()).invoke(any());

        // 场景 2：有 caller，走 pipelineEngine
        properties.getLingCoreGovernance().setGovernInternalCalls(true);
        LingCallContext.setLingId("caller-ling");
        proxy.doSomething();
        verify(pipelineEngine, times(1)).invoke(any());

        // 场景 3：调用 Object 方法 (例如 hashCode)
        proxy.hashCode();

        // 场景 4：根据方法名推导审计
        proxy.createSomething();

        // 场景 5：模拟 pipelineEngine 抛出安全拒绝异常
        doThrow(new com.lingframe.api.exception.LingInvocationException(
            "reject", com.lingframe.api.exception.LingInvocationException.ErrorKind.SECURITY_REJECTED, "denied"
        )).when(pipelineEngine).invoke(any());
        
        assertThrows(com.lingframe.api.exception.PermissionDeniedException.class, proxy::doSomething);

        // 场景 6：模拟 pipelineEngine 抛出其他治理异常
        doThrow(new com.lingframe.api.exception.LingInvocationException(
            "limit", com.lingframe.api.exception.LingInvocationException.ErrorKind.RATE_LIMITED, "limited"
        )).when(pipelineEngine).invoke(any());

        assertThrows(com.lingframe.api.exception.LingInvocationException.class, proxy::doSomething);
    }

}
