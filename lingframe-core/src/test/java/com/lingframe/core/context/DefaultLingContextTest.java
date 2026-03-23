package com.lingframe.core.context;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DefaultLingContext 测试")
class DefaultLingContextTest {

    @Nested
    @DisplayName("调用签名解析")
    class SignatureParsingTests {

        @Test
        @DisplayName("根据方法签名应解析参数类型名称")
        void invokeShouldParseParamTypeNamesFromSignature() {
            LingRepository lingRepository = mock(LingRepository.class);
            LingServiceRegistry registry = mock(LingServiceRegistry.class);
            InvocationPipelineEngine pipeline = mock(InvocationPipelineEngine.class);
            PermissionService permissionService = mock(PermissionService.class);
            EventBus eventBus = mock(EventBus.class);

            when(registry.getServiceClassName("svc")).thenReturn("com.example.Foo");
            when(registry.getProviderMethods("svc")).thenReturn(Arrays.asList("hello(java.lang.String, int)"));

            AtomicReference<String[]> capturedParamTypes = new AtomicReference<>();
            AtomicReference<String> capturedMethodName = new AtomicReference<>();
            when(pipeline.invoke(any())).thenAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);
                capturedParamTypes.set(ctx.getParameterTypeNames());
                capturedMethodName.set(ctx.getMethodName());
                return "ok";
            });

            DefaultLingContext context = new DefaultLingContext("ling-A", lingRepository, registry, pipeline,
                    permissionService, eventBus);

            Optional<Object> result = context.invoke("svc", "a", 1);
            assertTrue(result.isPresent());
            assertEquals("ok", result.get());
            assertEquals("hello", capturedMethodName.get());
            assertArrayEquals(new String[] { "java.lang.String", "int" }, capturedParamTypes.get());
        }

        @Test
        @DisplayName("无参方法签名应解析为空参数数组")
        void invokeShouldHandleNoParamSignature() {
            LingRepository lingRepository = mock(LingRepository.class);
            LingServiceRegistry registry = mock(LingServiceRegistry.class);
            InvocationPipelineEngine pipeline = mock(InvocationPipelineEngine.class);
            PermissionService permissionService = mock(PermissionService.class);
            EventBus eventBus = mock(EventBus.class);

            when(registry.getServiceClassName("svc")).thenReturn("com.example.Foo");
            when(registry.getProviderMethods("svc")).thenReturn(Arrays.asList("ping()"));

            AtomicReference<String[]> capturedParamTypes = new AtomicReference<>();
            when(pipeline.invoke(any())).thenAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);
                capturedParamTypes.set(ctx.getParameterTypeNames());
                return "ok";
            });

            DefaultLingContext context = new DefaultLingContext("ling-A", lingRepository, registry, pipeline,
                    permissionService, eventBus);

            Optional<Object> result = context.invoke("svc");
            assertTrue(result.isPresent());
            assertEquals("ok", result.get());
            assertArrayEquals(new String[0], capturedParamTypes.get());
        }
    }

    @Nested
    @DisplayName("调用上下文写入")
    class InvocationContextTests {

        @Test
        @DisplayName("跨灵元调用时应写入调用方与目标灵元标识")
        void invokeShouldSetCallerAndTargetLingId() {
            LingRepository lingRepository = mock(LingRepository.class);
            LingServiceRegistry registry = mock(LingServiceRegistry.class);
            InvocationPipelineEngine pipeline = mock(InvocationPipelineEngine.class);
            PermissionService permissionService = mock(PermissionService.class);
            EventBus eventBus = mock(EventBus.class);

            when(registry.getServiceClassName("ling-B:svc")).thenReturn("com.example.Foo");
            when(registry.getProviderMethods("ling-B:svc")).thenReturn(Arrays.asList("ping()"));

            AtomicReference<String> capturedCaller = new AtomicReference<>();
            AtomicReference<String> capturedTarget = new AtomicReference<>();
            when(pipeline.invoke(any())).thenAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);
                capturedCaller.set(ctx.getCallerLingId());
                capturedTarget.set(ctx.getTargetLingId());
                return "ok";
            });

            DefaultLingContext context = new DefaultLingContext("ling-A", lingRepository, registry, pipeline,
                    permissionService, eventBus);

            Optional<Object> result = context.invoke("ling-B:svc");
            assertTrue(result.isPresent());
            assertEquals("ok", result.get());
            assertEquals("ling-A", capturedCaller.get());
            assertEquals("ling-B", capturedTarget.get());
        }
    }
}
