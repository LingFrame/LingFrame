package com.lingframe.core.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.api.security.PermissionService;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoreLingContextTest {

    @Test
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

        CoreLingContext context = new CoreLingContext("ling-A", lingRepository, registry, pipeline,
                permissionService, eventBus);

        Optional<Object> result = context.invoke("svc", "a", 1);
        assertTrue(result.isPresent());
        assertEquals("ok", result.get());
        assertEquals("hello", capturedMethodName.get());
        assertArrayEquals(new String[] { "java.lang.String", "int" }, capturedParamTypes.get());
    }

    @Test
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

        CoreLingContext context = new CoreLingContext("ling-A", lingRepository, registry, pipeline,
                permissionService, eventBus);

        Optional<Object> result = context.invoke("svc");
        assertTrue(result.isPresent());
        assertEquals("ok", result.get());
        assertArrayEquals(new String[0], capturedParamTypes.get());
    }

    @Test
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

        CoreLingContext context = new CoreLingContext("ling-A", lingRepository, registry, pipeline,
                permissionService, eventBus);

        Optional<Object> result = context.invoke("ling-B:svc");
        assertTrue(result.isPresent());
        assertEquals("ok", result.get());
        assertEquals("ling-A", capturedCaller.get());
        assertEquals("ling-B", capturedTarget.get());
    }
}
