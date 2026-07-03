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
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.dashboard.dto.InvokeResultDTO;
import com.lingframe.dashboard.dto.ServiceMetadataDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicePlaygroundService 测试")
class ServicePlaygroundServiceTest {

    @Mock
    private LingServiceRegistry serviceRegistry;

    @Mock
    private LingRepository repository;

    @Mock
    private InvocationPipelineEngine pipelineEngine;

    @Mock
    private CanaryRouter canaryRouter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("getServices 元数据查询")
    class GetServicesTests {

        @Test
        @DisplayName("当灵元未注册任何服务时返回空列表")
        void shouldReturnEmptyListWhenNoServicesRegistered() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Collections.emptyList());

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertNotNull(services);
            assertTrue(services.isEmpty());
        }

        @Test
        @DisplayName("当灵元注册服务时应正确返回结构化元数据")
        void shouldReturnStructuredMetadataWhenServicesRegistered() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Arrays.asList("test-ling:EchoService"));
            when(serviceRegistry.getProviderMethods("test-ling:EchoService"))
                    .thenReturn(Arrays.asList("echo(java.lang.String)", "ping()"));
            when(serviceRegistry.getReturnType("test-ling:EchoService", "echo(java.lang.String)"))
                    .thenReturn("java.lang.String");
            when(serviceRegistry.getReturnType("test-ling:EchoService", "ping()")).thenReturn("void");

            // 模拟活跃实例，ClassLoader 为 null 时 isMethodAvailable 保留方法（向后兼容）
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getVersion()).thenReturn("1.0");
            when(instance.hasServiceMethod(any(), any(), any())).thenReturn(true);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertEquals(1, services.size());
            ServiceMetadataDTO serviceDto = services.get(0);
            assertEquals("test-ling:EchoService", serviceDto.getFqsid());
            assertNull(serviceDto.getClassName());
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
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);

            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Arrays.asList(
                    "test-ling:com.example.OrderService",
                    "test-ling:query_order"));
            when(serviceRegistry.getProviderMethods("test-ling:com.example.OrderService"))
                    .thenReturn(Arrays.asList("queryOrder(java.lang.Long)", "ping()"));
            when(serviceRegistry.getProviderMethods("test-ling:query_order"))
                    .thenReturn(Arrays.asList("queryOrder(java.lang.Long)", "ping()"));

            when(serviceRegistry.getReturnType("test-ling:com.example.OrderService", "queryOrder(java.lang.Long)"))
                    .thenReturn("com.example.Order");
            when(serviceRegistry.getReturnType("test-ling:com.example.OrderService", "ping()")).thenReturn("void");
            when(serviceRegistry.getReturnType("test-ling:query_order", "queryOrder(java.lang.Long)"))
                    .thenReturn("com.example.Order");
            when(serviceRegistry.getReturnType("test-ling:query_order", "ping()")).thenReturn("void");

            // 模拟活跃实例，ClassLoader 为 null 时保留方法（向后兼容）
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getVersion()).thenReturn("1.0");
            when(instance.hasServiceMethod(any(), any(), any())).thenReturn(true);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertEquals(1, services.size());
            ServiceMetadataDTO serviceDto = services.get(0);
            assertEquals("test-ling:com.example.OrderService", serviceDto.getFqsid());
            assertNull(serviceDto.getClassName());
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

        @Test
        @DisplayName("当同时注册了接口服务和显式注解服务，但它们的实现类名不同时，不应合并它们")
        void shouldNotMergeExplicitServicesIntoInterfaceServicesIfImplementationClassNamesDiffer() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);

            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Arrays.asList(
                    "test-ling:com.example.UserService",
                    "test-ling:delete_user"));
            when(serviceRegistry.getProviderMethods("test-ling:com.example.UserService"))
                    .thenReturn(Arrays.asList("deleteUser(java.lang.String)"));
            when(serviceRegistry.getProviderMethods("test-ling:delete_user"))
                    .thenReturn(Arrays.asList("deleteUser(java.lang.String)"));

            when(serviceRegistry.getReturnType("test-ling:com.example.UserService", "deleteUser(java.lang.String)"))
                    .thenReturn("boolean");
            when(serviceRegistry.getReturnType("test-ling:delete_user", "deleteUser(java.lang.String)"))
                    .thenReturn("boolean");

            // 两个服务注册了不同的实现类名
            when(serviceRegistry.getImplementationClassName("test-ling:com.example.UserService"))
                    .thenReturn("com.example.UserServiceImpl");
            when(serviceRegistry.getImplementationClassName("test-ling:delete_user"))
                    .thenReturn("com.example.CanaryUserServiceImpl");

            // 模拟活跃实例
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getVersion()).thenReturn("1.0");
            when(instance.hasServiceMethod(any(), any(), any())).thenReturn(true);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            // 因为实现类名不一致，没有被合并，所以应该返回 2 个独立的服务卡片
            assertEquals(2, services.size());
        }

        @Test
        @DisplayName("当同一个方法注册了多个不同的显式服务别名时，为了防止 alternateFqsid 覆盖冲突，不应归并它们")
        void shouldNotMergeExplicitServicesIfMultipleDifferentAliasesExistForSameMethod() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);

            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Arrays.asList(
                    "test-ling:com.example.UserService",
                    "test-ling:delete_user",
                    "test-ling:canary_delete_user"));
            when(serviceRegistry.getProviderMethods("test-ling:com.example.UserService"))
                    .thenReturn(Arrays.asList("deleteUser(java.lang.String)"));
            when(serviceRegistry.getProviderMethods("test-ling:delete_user"))
                    .thenReturn(Arrays.asList("deleteUser(java.lang.String)"));
            when(serviceRegistry.getProviderMethods("test-ling:canary_delete_user"))
                    .thenReturn(Arrays.asList("deleteUser(java.lang.String)"));

            when(serviceRegistry.getReturnType("test-ling:com.example.UserService", "deleteUser(java.lang.String)"))
                    .thenReturn("boolean");
            when(serviceRegistry.getReturnType("test-ling:delete_user", "deleteUser(java.lang.String)"))
                    .thenReturn("boolean");
            when(serviceRegistry.getReturnType("test-ling:canary_delete_user", "deleteUser(java.lang.String)"))
                    .thenReturn("boolean");

            // 它们具有完全一致的实现类名（同包同名，只是逻辑/版本不同）
            when(serviceRegistry.getImplementationClassName("test-ling:com.example.UserService"))
                    .thenReturn("com.example.UserServiceImpl");
            when(serviceRegistry.getImplementationClassName("test-ling:delete_user"))
                    .thenReturn("com.example.UserServiceImpl");
            when(serviceRegistry.getImplementationClassName("test-ling:canary_delete_user"))
                    .thenReturn("com.example.UserServiceImpl");

            // 模拟活跃实例
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getVersion()).thenReturn("1.0");
            when(instance.hasServiceMethod(any(), any(), any())).thenReturn(true);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            // 包含 1 个接口卡片和 2 个不同的显式服务卡片，共 3 个独立卡片
            assertEquals(3, services.size());
        }

        @Test
        @DisplayName("当调用 getServices 时，若显式服务方法在不同版本的 ClassLoader 中解析结果不同，应正确附带其可用的版本列表")
        void shouldOnlyAttachAvailableVersionsForExplicitService() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);

            String fqsid = "test-ling:query_user";
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Collections.singletonList(fqsid));
            when(serviceRegistry.getProviderMethods(fqsid)).thenReturn(Arrays.asList("query(java.lang.String)"));
            when(serviceRegistry.getReturnType(fqsid, "query(java.lang.String)")).thenReturn("java.lang.String");
            when(serviceRegistry.getImplementationClassName(fqsid))
                    .thenReturn("com.lingframe.dashboard.service.ServicePlaygroundServiceTest$DummyQueryService");

            // 模拟两个活跃实例：v1（稳定版）和 v2（金丝雀）
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance stable = mock(LingInstance.class);
            LingInstance canary = mock(LingInstance.class);

            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Arrays.asList(stable, canary));
            when(stable.getVersion()).thenReturn("1.0");
            when(canary.getVersion()).thenReturn("2.0");

            // v1 不包含此服务方法
            when(stable.hasServiceMethod(any(), any(), any())).thenReturn(false);

            // v2 包含此服务方法
            when(canary.hasServiceMethod(any(), any(), any())).thenReturn(true);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertEquals(1, services.size());
            ServiceMetadataDTO serviceDto = services.get(0);
            assertEquals(1, serviceDto.getMethods().size());

            ServiceMetadataDTO.MethodMetadata method = serviceDto.getMethods().get(0);
            // 应该只包含 2.0 版本，而不包含 1.0 版本
            assertEquals(Collections.singletonList("2.0"), method.getVersions());
        }
    }

    @Nested
    @DisplayName("invokeService 真实服务调用")
    class InvokeServiceTests {

        @Test
        @DisplayName("调用不存在的灵元时返回失败")
        void shouldReturnErrorWhenLingNotExists() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            when(repository.getRuntime("non-exist")).thenReturn(null);

            InvokeResultDTO result = playgroundService.invokeService("non-exist", "non-exist:Service", "hello",
                    new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Ling not found"));
        }

        @Test
        @DisplayName("调用不可用的灵元时返回失败")
        void shouldReturnErrorWhenLingNotAvailable() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(false);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InvokeResultDTO result = playgroundService.invokeService("test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Ling not available"));
        }

        @Test
        @DisplayName("调用成功时应返回正确结果和治理轨迹并安全回收上下文")
        void shouldReturnSuccessResultAndTraceWhenInvokeSucceeds() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            // 模拟可用实例
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.isReady()).thenReturn(true);
            when(instance.getClassLoader()).thenReturn(this.getClass().getClassLoader());
            when(instance.getVersion()).thenReturn("1.0");

            EngineTrace trace = EngineTrace.builder()
                    .source("filter-1")
                    .action("action-1")
                    .type("PASS")
                    .build();
            doAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);
                assertEquals("test-ling", ctx.getTargetLingId());
                assertEquals("test-ling:Service", ctx.getServiceFQSID());
                assertNull(ctx.resolution().getTargetClassName());
                assertEquals("hello", ctx.getMethodName());
                assertEquals("dashboard", ctx.getCallerLingId());
                ctx.execution().addTrace(trace);
                return "hello-result";
            }).when(pipelineEngine).invoke(any(InvocationContext.class));

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling",
                    "test-ling:Service",
                    "hello",
                    new String[] { "java.lang.String" },
                    new Object[] { "world" },
                    null);

            assertTrue(result.isSuccess());
            assertEquals("hello-result", result.getResult());
            assertEquals(1, result.getTraces().size());
            assertEquals("filter-1", result.getTraces().get(0).getSource());
            assertEquals("action-1", result.getTraces().get(0).getAction());
            assertEquals("PASS", result.getTraces().get(0).getType());

            // 检查当前线程 ThreadLocal 中没有挂着脏数据，或者由于执行了 recycle，InvocationContext.current() 应为
            // null
            assertNull(InvocationContext.current());
        }

        @Test
        @DisplayName("调用抛出异常时应捕获并返回错误且安全回收上下文")
        void shouldReturnErrorResultWhenInvokeThrowsException() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            // 模拟可用实例
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.isReady()).thenReturn(true);
            when(instance.getClassLoader()).thenReturn(this.getClass().getClassLoader());
            when(instance.getVersion()).thenReturn("1.0");

            when(pipelineEngine.invoke(any(InvocationContext.class))).thenThrow(new RuntimeException("biz error"));

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling",
                    "test-ling:Service",
                    "hello",
                    new String[] { "java.lang.String" },
                    new Object[] { "world" },
                    null);

            assertFalse(result.isSuccess());
            assertEquals("biz error", result.getError());
            assertNull(InvocationContext.current());
        }

        @Test
        @DisplayName("调用服务时若参数类型不匹配，应自动进行类型强转")
        void shouldConvertArgumentsWhenTypesMismatch() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.isReady()).thenReturn(true);
            when(instance.getClassLoader()).thenReturn(this.getClass().getClassLoader());
            when(instance.getVersion()).thenReturn("1.0");

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
                    new String[] { "java.lang.Long" },
                    new Object[] { 123 },
                    null);

            assertTrue(result.isSuccess());
            assertEquals("convert-success", result.getResult());
        }

        @Test
        @DisplayName("按比例路由模式应返回实际路由版本")
        void shouldReturnRoutedVersionInProportionalMode() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getLingId()).thenReturn("test-ling");
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            // 模拟双版本实例
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance stable = mock(LingInstance.class);
            LingInstance canary = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Arrays.asList(stable, canary));
            when(instancePool.getDefault()).thenReturn(stable);
            when(stable.getVersion()).thenReturn("1.0");
            when(stable.isReady()).thenReturn(true);
            when(stable.getClassLoader()).thenReturn(this.getClass().getClassLoader());
            when(canary.getVersion()).thenReturn("2.0");
            when(canary.isReady()).thenReturn(true);
            when(canary.getClassLoader()).thenReturn(this.getClass().getClassLoader());

            // 金丝雀比例 100%，必然路由到金丝雀版
            when(canaryRouter.getCanaryConfig("test-ling"))
                    .thenReturn(new CanaryRouter.CanaryConfig(100, "2.0"));

            when(pipelineEngine.invoke(any(InvocationContext.class))).thenReturn("ok");

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], null, "PROPORTIONAL");

            assertTrue(result.isSuccess());
            assertEquals("2.0", result.getRoutedVersion());
        }

        @Test
        @DisplayName("按比例路由模式金丝雀比例为0时应路由到稳定版")
        void shouldRouteToStableWhenCanaryPercentZero() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getLingId()).thenReturn("test-ling");
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance stable = mock(LingInstance.class);
            LingInstance canary = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Arrays.asList(stable, canary));
            when(instancePool.getDefault()).thenReturn(stable);
            when(stable.getVersion()).thenReturn("1.0");
            when(stable.isReady()).thenReturn(true);
            when(stable.getClassLoader()).thenReturn(this.getClass().getClassLoader());
            when(canary.getVersion()).thenReturn("2.0");

            // 金丝雀比例 0%
            when(canaryRouter.getCanaryConfig("test-ling"))
                    .thenReturn(new CanaryRouter.CanaryConfig(0, "2.0"));

            when(pipelineEngine.invoke(any(InvocationContext.class))).thenReturn("ok");

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], null, "PROPORTIONAL");

            assertTrue(result.isSuccess());
            assertEquals("1.0", result.getRoutedVersion());
        }

        @Test
        @DisplayName("按比例路由模式单版本时应退化为该版本")
        void shouldDegradeToSingleInstanceInProportionalMode() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getLingId()).thenReturn("test-ling");
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance stable = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(stable));
            when(stable.getVersion()).thenReturn("1.0");
            when(stable.isReady()).thenReturn(true);
            when(stable.getClassLoader()).thenReturn(this.getClass().getClassLoader());

            when(pipelineEngine.invoke(any(InvocationContext.class))).thenReturn("ok");

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], null, "PROPORTIONAL");

            assertTrue(result.isSuccess());
            assertEquals("1.0", result.getRoutedVersion());
        }
    }

    public static class DummyQueryService {
        public String query(String param) {
            return param;
        }
    }
}
