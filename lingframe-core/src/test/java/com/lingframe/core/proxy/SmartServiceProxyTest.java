package com.lingframe.core.proxy;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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

    @Test
    void invoke_WhenObjectMethod_ShouldExecuteLocally() {
        assertNotNull(proxyInstance.toString());
        assertEquals(proxyInstance.hashCode(), proxyInstance.hashCode());
        // verify pipeline Engine was not called
        verifyNoInteractions(pipelineEngine);
    }

    @Test
    void invoke_WhenServiceMethod_ShouldContextBePopulatedAndReset() throws Throwable {
        when(pipelineEngine.invoke(any(InvocationContext.class))).thenAnswer(invocation -> {
            InvocationContext ctx = invocation.getArgument(0);

            // Assert context properties during execution
            assertEquals("target-ling:com.lingframe.core.proxy.SmartServiceProxyTest$DemoService",
                    ctx.getServiceFQSID());
            assertEquals("sayHello", ctx.getMethodName());
            assertEquals("target-ling", ctx.getTargetLingId());
            assertEquals("caller-ling", ctx.getCallerLingId());
            assertEquals(AccessType.EXECUTE, ctx.getAccessType());
            assertNotNull(ctx.getAttachments().get("ling.method.paramTypes"));
            assertArrayEquals(new Object[] { "World" }, ctx.getArgs());

            return "Hello World";
        });

        String result = proxyInstance.sayHello("World");

        assertEquals("Hello World", result);

        // After invocation, the ThreadLocal context MUST be reset
        InvocationContext afterCtx = InvocationContext.obtain();
        assertNull(afterCtx.getServiceFQSID());
        assertNull(afterCtx.getMethodName());
        assertNull(afterCtx.getArgs());
        assertTrue(afterCtx.getAttachments() == null || afterCtx.getAttachments().isEmpty());
    }

    @Test
    void invoke_WhenPipelineThrowsException_ShouldThrowDirectlyAndResetContext() throws Throwable {
        RuntimeException bizException = new RuntimeException("Business Rule Violated");
        when(pipelineEngine.invoke(any(InvocationContext.class))).thenThrow(bizException);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            proxyInstance.throwError();
        });

        assertEquals("Business Rule Violated", thrown.getMessage());

        // Context still must be reset
        InvocationContext afterCtx = InvocationContext.obtain();
        assertNull(afterCtx.getServiceFQSID());
    }
}
