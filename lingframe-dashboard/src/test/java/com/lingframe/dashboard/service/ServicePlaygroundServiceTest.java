package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.dashboard.dto.InvokeResultDTO;
import com.lingframe.dashboard.dto.ServiceMetadataDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicePlaygroundService 测试")
class ServicePlaygroundServiceTest {

    @Mock
    private LingServiceRegistry serviceRegistry;

    @Mock
    private LingRepository repository;

    @Mock
    private InvocationPipelineEngine pipelineEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("getServices 元数据查询")
    class GetServicesTests {

        @Test
        @DisplayName("当灵元未注册任何服务时返回空列表")
        void shouldReturnEmptyListWhenNoServicesRegistered() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Collections.emptyList());

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertNotNull(services);
            assertTrue(services.isEmpty());
        }

        @Test
        @DisplayName("当灵元注册服务时应正确返回结构化元数据")
        void shouldReturnStructuredMetadataWhenServicesRegistered() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Arrays.asList("test-ling:EchoService"));
            when(serviceRegistry.getServiceClassName("test-ling:EchoService")).thenReturn("com.example.EchoServiceImpl");
            when(serviceRegistry.getProviderMethods("test-ling:EchoService")).thenReturn(Arrays.asList("echo(java.lang.String)", "ping()"));
            when(serviceRegistry.getReturnType("test-ling:EchoService", "echo(java.lang.String)")).thenReturn("java.lang.String");
            when(serviceRegistry.getReturnType("test-ling:EchoService", "ping()")).thenReturn("void");

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertEquals(1, services.size());
            ServiceMetadataDTO serviceDto = services.get(0);
            assertEquals("test-ling:EchoService", serviceDto.getFqsid());
            assertEquals("com.example.EchoServiceImpl", serviceDto.getClassName());
            assertEquals(2, serviceDto.getMethods().size());

            ServiceMetadataDTO.MethodMetadata echoMethod = serviceDto.getMethods().get(0);
            assertEquals("echo", echoMethod.getName());
            assertEquals(Arrays.asList("java.lang.String"), echoMethod.getParameterTypes());
            assertEquals("String", echoMethod.getReturnType());
            assertEquals("echo(java.lang.String)", echoMethod.getSignature());

            ServiceMetadataDTO.MethodMetadata pingMethod = serviceDto.getMethods().get(1);
            assertEquals("ping", pingMethod.getName());
            assertTrue(pingMethod.getParameterTypes().isEmpty());
            assertEquals("void", pingMethod.getReturnType());
            assertEquals("ping()", pingMethod.getSignature());
        }

        @Test
        @DisplayName("当同时注册了接口服务和显式注解服务时，应合并显式服务为接口服务方法的别名并过滤掉显式服务卡片")
        void shouldMergeExplicitServicesIntoInterfaceServices() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Arrays.asList(
                    "test-ling:com.example.OrderService",
                    "test-ling:query_order"
            ));
            
            when(serviceRegistry.getServiceClassName("test-ling:com.example.OrderService")).thenReturn("com.example.OrderServiceImpl");
            when(serviceRegistry.getServiceClassName("test-ling:query_order")).thenReturn("com.example.OrderServiceImpl");
            
            when(serviceRegistry.getProviderMethods("test-ling:com.example.OrderService")).thenReturn(Arrays.asList("queryOrder(java.lang.Long)", "ping()"));
            when(serviceRegistry.getProviderMethods("test-ling:query_order")).thenReturn(Arrays.asList("queryOrder(java.lang.Long)", "ping()"));
            
            when(serviceRegistry.getReturnType("test-ling:com.example.OrderService", "queryOrder(java.lang.Long)")).thenReturn("com.example.Order");
            when(serviceRegistry.getReturnType("test-ling:com.example.OrderService", "ping()")).thenReturn("void");
            when(serviceRegistry.getReturnType("test-ling:query_order", "queryOrder(java.lang.Long)")).thenReturn("com.example.Order");
            when(serviceRegistry.getReturnType("test-ling:query_order", "ping()")).thenReturn("void");

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertEquals(1, services.size());
            ServiceMetadataDTO serviceDto = services.get(0);
            assertEquals("test-ling:com.example.OrderService", serviceDto.getFqsid());
            assertEquals("com.example.OrderServiceImpl", serviceDto.getClassName());
            assertEquals(2, serviceDto.getMethods().size());

            ServiceMetadataDTO.MethodMetadata queryMethod = serviceDto.getMethods().get(0);
            assertEquals("queryOrder", queryMethod.getName());
            assertEquals(Arrays.asList("java.lang.Long"), queryMethod.getParameterTypes());
            assertEquals("Order", queryMethod.getReturnType());
            assertEquals("test-ling:query_order", queryMethod.getAlternateFqsid());

            ServiceMetadataDTO.MethodMetadata pingMethod = serviceDto.getMethods().get(1);
            assertEquals("ping", pingMethod.getName());
            assertEquals("test-ling:query_order", pingMethod.getAlternateFqsid());
        }
    }

    @Nested
    @DisplayName("invokeService 真实服务调用")
    class InvokeServiceTests {

        @Test
        @DisplayName("调用不存在的灵元时返回失败")
        void shouldReturnErrorWhenLingNotExists() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            when(repository.getRuntime("non-exist")).thenReturn(null);

            InvokeResultDTO result = playgroundService.invokeService("non-exist", "non-exist:Service", "hello", new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Ling not found"));
        }

        @Test
        @DisplayName("调用不可用的灵元时返回失败")
        void shouldReturnErrorWhenLingNotAvailable() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(false);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InvokeResultDTO result = playgroundService.invokeService("test-ling", "test-ling:Service", "hello", new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Ling not available"));
        }

        @Test
        @DisplayName("调用成功时应返回正确结果和治理轨迹并安全回收上下文")
        void shouldReturnSuccessResultAndTraceWhenInvokeSucceeds() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(serviceRegistry.getServiceClassName("test-ling:Service")).thenReturn("com.example.Service");

            EngineTrace trace = EngineTrace.builder()
                    .source("filter-1")
                    .action("action-1")
                    .type("PASS")
                    .build();
            doAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);
                assertEquals("test-ling", ctx.getTargetLingId());
                assertEquals("test-ling:Service", ctx.getServiceFQSID());
                assertEquals("com.example.Service", ctx.resolution().getTargetClassName());
                assertEquals("hello", ctx.getMethodName());
                assertEquals("dashboard", ctx.getCallerLingId());
                ctx.addTrace(trace);
                return "hello-result";
            }).when(pipelineEngine).invoke(any(InvocationContext.class));

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling",
                    "test-ling:Service",
                    "hello",
                    new String[]{"java.lang.String"},
                    new Object[]{"world"},
                    null
            );

            assertTrue(result.isSuccess());
            assertEquals("hello-result", result.getResult());
            assertEquals(1, result.getTraces().size());
            assertEquals("filter-1", result.getTraces().get(0).getSource());
            assertEquals("action-1", result.getTraces().get(0).getAction());
            assertEquals("PASS", result.getTraces().get(0).getType());

            // 检查当前线程 ThreadLocal 中没有挂着脏数据，或者由于执行了 recycle，InvocationContext.current() 应为 null
            assertNull(InvocationContext.current());
        }

        @Test
        @DisplayName("调用抛出异常时应捕获并返回错误且安全回收上下文")
        void shouldReturnErrorResultWhenInvokeThrowsException() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(serviceRegistry.getServiceClassName("test-ling:Service")).thenReturn("com.example.Service");

            when(pipelineEngine.invoke(any(InvocationContext.class))).thenThrow(new RuntimeException("biz error"));

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling",
                    "test-ling:Service",
                    "hello",
                    new String[]{"java.lang.String"},
                    new Object[]{"world"},
                    null
            );

            assertFalse(result.isSuccess());
            assertEquals("biz error", result.getError());
            assertNull(InvocationContext.current());
        }

        @Test
        @DisplayName("调用服务时若参数类型不匹配，应自动进行类型强转")
        void shouldConvertArgumentsWhenTypesMismatch() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository, pipelineEngine, objectMapper);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(serviceRegistry.getServiceClassName("test-ling:Service")).thenReturn("com.example.Service");

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getClassLoader()).thenReturn(this.getClass().getClassLoader());

            doAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);
                Object[] args = ctx.getArgs();
                assertEquals(1, args.length);
                assertTrue(args[0] instanceof Long);
                assertEquals(123L, args[0]);
                return "convert-success";
            }).when(pipelineEngine).invoke(any(InvocationContext.class));

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling",
                    "test-ling:Service",
                    "queryOrder",
                    new String[]{"java.lang.Long"},
                    new Object[]{123},
                    null
            );

            assertTrue(result.isSuccess());
            assertEquals("convert-success", result.getResult());
        }
    }
}
