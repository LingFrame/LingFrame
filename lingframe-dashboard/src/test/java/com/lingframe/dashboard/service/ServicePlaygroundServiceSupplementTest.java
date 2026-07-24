package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.router.CanaryRouter;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ServicePlaygroundService 补充测试。
 * <p>
 * 覆盖 ServicePlaygroundServiceTest 中未覆盖的边界场景：
 * <ul>
 *   <li>getServices：fqsidList 为 null、方法在所有版本不可用被过滤、服务整体被过滤</li>
 *   <li>invokeService：runtime 不存在/不可用（精确错误信息）、目标版本不存在、无可用实例、
 *       实例未就绪、参数转换失败、ClassLoader 为 null 不污染 TCCL、成功路径耗时与路由版本</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ServicePlaygroundService 补充测试")
class ServicePlaygroundServiceSupplementTest {

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
    @DisplayName("getServices 元数据查询补充场景")
    class GetServicesSupplementTests {

        @Test
        @DisplayName("当 fqsidList 为 null 时返回空列表")
        void shouldReturnEmptyListWhenFqsidListIsNull() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(null);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertNotNull(services);
            assertTrue(services.isEmpty());
        }

        @Test
        @DisplayName("当所有方法在所有版本都不可用时应过滤掉整个服务")
        void shouldFilterOutServiceWhenAllMethodsUnavailableInAllVersions() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            String fqsid = "test-ling:EchoService";
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Collections.singletonList(fqsid));
            when(serviceRegistry.getProviderMethods(fqsid)).thenReturn(Arrays.asList("echo()", "ping()"));
            when(serviceRegistry.getReturnType(fqsid, "echo()")).thenReturn("java.lang.String");
            when(serviceRegistry.getReturnType(fqsid, "ping()")).thenReturn("void");

            // 模拟活跃实例，但所有方法在所有版本上都不可用
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getVersion()).thenReturn("1.0");
            when(instance.hasServiceMethod(any(), any(), any())).thenReturn(false);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            // 所有方法都被过滤，服务也被过滤，结果为空
            assertNotNull(services);
            assertTrue(services.isEmpty());
        }

        @Test
        @DisplayName("当部分方法在所有版本不可用时应只过滤不可用方法，保留可用方法")
        void shouldOnlyFilterOutUnavailableMethodsAndKeepAvailableOnes() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            String fqsid = "test-ling:EchoService";
            when(serviceRegistry.getServicesByLingId("test-ling")).thenReturn(Collections.singletonList(fqsid));
            when(serviceRegistry.getProviderMethods(fqsid)).thenReturn(Arrays.asList("echo()", "ping()"));
            when(serviceRegistry.getReturnType(fqsid, "echo()")).thenReturn("java.lang.String");
            when(serviceRegistry.getReturnType(fqsid, "ping()")).thenReturn("void");

            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(instance.getVersion()).thenReturn("1.0");

            // echo 在版本上可用，ping 不可用
            when(instance.hasServiceMethod(eq(fqsid), eq("echo"), any())).thenReturn(true);
            when(instance.hasServiceMethod(eq(fqsid), eq("ping"), any())).thenReturn(false);

            List<ServiceMetadataDTO> services = playgroundService.getServices("test-ling");

            assertEquals(1, services.size());
            ServiceMetadataDTO serviceDto = services.get(0);
            // ping 被过滤，只保留 echo
            assertEquals(1, serviceDto.getMethods().size());
            assertEquals("echo", serviceDto.getMethods().get(0).getName());
            assertEquals(Collections.singletonList("1.0"), serviceDto.getMethods().get(0).getVersions());
        }
    }

    @Nested
    @DisplayName("invokeService 异常路径补充场景")
    class InvokeServiceSupplementTests {

        @Test
        @DisplayName("当 runtime 不存在时返回包含灵元 ID 的精确错误信息")
        void shouldReturnExactErrorWhenLingNotFound() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            when(repository.getRuntime("ling1")).thenReturn(null);

            InvokeResultDTO result = playgroundService.invokeService("ling1", "ling1:Service", "hello",
                    new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertEquals("Ling not found: ling1", result.getError());
        }

        @Test
        @DisplayName("当 runtime 不可用时返回包含灵元 ID 的精确错误信息")
        void shouldReturnExactErrorWhenLingNotAvailable() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(false);
            when(repository.getRuntime("ling1")).thenReturn(runtime);

            InvokeResultDTO result = playgroundService.invokeService("ling1", "ling1:Service", "hello",
                    new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertEquals("Ling not available: ling1", result.getError());
        }

        @Test
        @DisplayName("当指定版本但找不到对应实例且无兜底实例时返回 Target version not available 错误")
        void shouldReturnErrorWhenTargetVersionNotAvailable() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            // 指定版本不存在，且无默认实例和活跃实例兜底
            when(instancePool.getInstance("9.9")).thenReturn(null);
            when(instancePool.getDefault()).thenReturn(null);
            when(instancePool.getActiveInstances()).thenReturn(Collections.emptyList());

            InvokeResultDTO result = playgroundService.invokeService("test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], "9.9");

            assertFalse(result.isSuccess());
            assertEquals("Target version not available: 9.9", result.getError());
        }

        @Test
        @DisplayName("当未指定版本且无任何可用实例时返回 No available instance 错误")
        void shouldReturnErrorWhenNoAvailableInstance() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(null);
            when(instancePool.getActiveInstances()).thenReturn(Collections.emptyList());

            InvokeResultDTO result = playgroundService.invokeService("test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertEquals("No available instance", result.getError());
        }

        @Test
        @DisplayName("当目标实例未就绪时返回 Target instance not ready 错误并安全回收上下文")
        void shouldReturnErrorWhenTargetInstanceNotReady() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.getVersion()).thenReturn("1.0");
            // ClassLoader 为 null，避免污染 TCCL
            when(instance.getClassLoader()).thenReturn(null);
            when(instance.isReady()).thenReturn(false);
            when(instance.currentStatus()).thenReturn(InstanceStatus.STARTING);

            InvokeResultDTO result = playgroundService.invokeService("test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], null);

            assertFalse(result.isSuccess());
            assertEquals("Target instance not ready: 1.0 (status=STARTING)", result.getError());
            // 上下文应被 recycle 回收，不残留
            assertNull(InvocationContext.current());
        }

        @Test
        @DisplayName("当参数转换失败时返回 Parameter conversion failed 错误")
        void shouldReturnErrorWhenParameterConversionFails() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.isReady()).thenReturn(true);
            when(instance.getClassLoader()).thenReturn(this.getClass().getClassLoader());
            when(instance.getVersion()).thenReturn("1.0");

            // 传入无法转换的参数：int 类型接收非数字字符串
            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling", "test-ling:Service", "hello",
                    new String[] { "int" },
                    new Object[] { "not-a-number" },
                    null);

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Parameter conversion failed"));
        }

        @Test
        @DisplayName("当实例的 ClassLoader 为 null 时不应改变线程上下文类加载器")
        void shouldNotChangeContextClassLoaderWhenInstanceClassLoaderIsNull() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.isReady()).thenReturn(true);
            // 关键：ClassLoader 返回 null，不应触发 TCCL 切换
            when(instance.getClassLoader()).thenReturn(null);
            when(instance.getVersion()).thenReturn("1.0");

            ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
            ClassLoader[] capturedCl = new ClassLoader[1];
            doAnswer(invocation -> {
                // 捕获调用期间的 TCCL，验证未被切换
                capturedCl[0] = Thread.currentThread().getContextClassLoader();
                return "ok";
            }).when(pipelineEngine).invoke(any(InvocationContext.class));

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling", "test-ling:Service", "hello",
                    new String[0], new Object[0], null);

            assertTrue(result.isSuccess());
            assertEquals("ok", result.getResult());
            // 调用期间和调用后，TCCL 都应保持不变
            assertEquals(originalCl, capturedCl[0]);
            assertEquals(originalCl, Thread.currentThread().getContextClassLoader());
        }

        @Test
        @DisplayName("SPECIFIED 模式成功调用时应返回正数耗时和 null 路由版本")
        void shouldReturnSuccessWithDurationAndTargetRoutedVersion() {
            ServicePlaygroundService playgroundService = new ServicePlaygroundService(serviceRegistry, repository,
                    pipelineEngine, objectMapper, canaryRouter, null, null);
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.isAvailable()).thenReturn(true);
            when(repository.getRuntime("test-ling")).thenReturn(runtime);

            InstancePool instancePool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(instance);
            when(instance.isReady()).thenReturn(true);
            when(instance.getClassLoader()).thenReturn(this.getClass().getClassLoader());
            when(instance.getVersion()).thenReturn("1.0");

            // 通过 sleep 确保 durationMs > 0，规避系统时钟粒度
            doAnswer(invocation -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "hello-result";
            }).when(pipelineEngine).invoke(any(InvocationContext.class));

            InvokeResultDTO result = playgroundService.invokeService(
                    "test-ling", "test-ling:Service", "hello",
                    new String[] { "java.lang.String" },
                    new Object[] { "world" },
                    null);

            assertTrue(result.isSuccess());
            assertEquals("hello-result", result.getResult());
            // SPECIFIED 也回填实际命中版本，便于前端展示与审计（不限于 PROPORTIONAL）
            assertEquals("1.0", result.getRoutedVersion());
            assertEquals("NORMAL", result.getExecutionMode());
            assertTrue(result.isSideEffects());
            assertTrue(result.getDurationMs() > 0);
        }
    }
}
