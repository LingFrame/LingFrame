package com.lingframe.core.proxy;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmartServiceProxy 测试")
class SmartServiceProxyTest {

    @Mock
    private InvocationPipelineEngine pipelineEngine;

    private SmartServiceProxy smartServiceProxy;
    private DemoService proxyInstance;

    interface DemoService {
        String sayHello(String name);

        void throwError() throws Exception;
    }

    @BeforeEach
    void setUp() {
        smartServiceProxy = new SmartServiceProxy("caller-ling", "target-ling", pipelineEngine);
        proxyInstance = (DemoService) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class[] { DemoService.class },
                smartServiceProxy);
    }

    @AfterEach
    void tearDown() {
        InvocationContext.obtain().reset();
    }

    @Nested
    @DisplayName("Object 基础方法")
    class ObjectMethodTests {

        @Test
        @DisplayName("调用 Object 方法时应在本地执行")
        void invoke_WhenObjectMethod_ShouldExecuteLocally() {
            assertNotNull(proxyInstance.toString());
            assertEquals(proxyInstance.hashCode(), proxyInstance.hashCode());
            verifyNoInteractions(pipelineEngine);
        }
    }

    @Nested
    @DisplayName("服务调用")
    class ServiceInvocationTests {

        @Test
        @DisplayName("服务方法调用时应填充并在结束后重置上下文")
        void invoke_WhenServiceMethod_ShouldContextBePopulatedAndReset() throws Throwable {
            when(pipelineEngine.invoke(any(InvocationContext.class))).thenAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);

                assertEquals("target-ling:com.lingframe.core.proxy.SmartServiceProxyTest$DemoService",
                        ctx.getServiceFQSID());
                assertEquals("sayHello", ctx.getMethodName());
                assertEquals("target-ling", ctx.getTargetLingId());
                assertEquals("caller-ling", ctx.getCallerLingId());
                assertEquals(AccessType.EXECUTE, ctx.getAccessType());
                assertNotNull(ctx.getParameterTypeNames());
                assertArrayEquals(new Object[] { "World" }, ctx.getArgs());

                return "Hello World";
            });

            String result = proxyInstance.sayHello("World");

            assertEquals("Hello World", result);

            InvocationContext afterCtx = InvocationContext.obtain();
            assertNull(afterCtx.getServiceFQSID());
            assertNull(afterCtx.getMethodName());
            assertNull(afterCtx.getArgs());
            assertTrue(afterCtx.getAttachments() == null || afterCtx.getAttachments().isEmpty());
        }

        @Test
        @DisplayName("管线抛出异常时应直接透传并重置上下文")
        void invoke_WhenPipelineThrowsException_ShouldThrowDirectlyAndResetContext() throws Throwable {
            RuntimeException bizException = new RuntimeException("Business Rule Violated");
            when(pipelineEngine.invoke(any(InvocationContext.class))).thenThrow(bizException);

            RuntimeException thrown = assertThrows(RuntimeException.class, () -> proxyInstance.throwError());

            assertEquals("Business Rule Violated", thrown.getMessage());

            InvocationContext afterCtx = InvocationContext.obtain();
            assertNull(afterCtx.getServiceFQSID());
        }
    }
}
