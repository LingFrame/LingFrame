package com.lingframe.dashboard.service;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.api.security.PermissionService;
import com.lingframe.dashboard.dto.SimulateResultDTO;
import com.lingframe.dashboard.dto.StressResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模拟服务测试
 * 覆盖 simulateResource / simulateIpc / stressTest / simulateMethod
 * 含各方法的异常路径（runtime 缺失/不可用/无实例）与部分成功路径
 */
@DisplayName("模拟服务测试")
class SimulateServiceTest {

    private LingRepository lingRepository;
    private EventBus eventBus;
    private CanaryRouter canaryRouter;
    private PermissionService permissionService;
    private InvocationPipelineEngine pipelineEngine;
    private LingFrameInfo lingFrameInfo;
    private SimulateService service;

    @BeforeEach
    void setUp() {
        lingRepository = mock(LingRepository.class);
        eventBus = mock(EventBus.class);
        canaryRouter = mock(CanaryRouter.class);
        permissionService = mock(PermissionService.class);
        pipelineEngine = mock(InvocationPipelineEngine.class);
        lingFrameInfo = mock(LingFrameInfo.class);
        service = new SimulateService(lingRepository, eventBus, canaryRouter,
                permissionService, pipelineEngine, lingFrameInfo);
    }

    // ==================== simulateResource ====================

    @Nested
    @DisplayName("simulateResource")
    class SimulateResourceTests {

        @Test
        @DisplayName("runtime 不存在时抛 LingNotFoundException")
        void shouldThrowWhenRuntimeNotFound() {
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            assertThrows(LingNotFoundException.class,
                    () -> service.simulateResource("ling1", "dbRead"));
        }

        @Test
        @DisplayName("runtime 不可用时抛 LingInvocationException")
        void shouldThrowWhenRuntimeUnavailable() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(false);

            assertThrows(LingInvocationException.class,
                    () -> service.simulateResource("ling1", "dbRead"));
        }

        @Test
        @DisplayName("Pipeline 接受调用时应返回 allowed=true")
        void shouldReturnAllowedWhenPipelineAccepts() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(pipelineEngine.invoke(any(InvocationContext.class))).thenReturn("ok");

            SimulateResultDTO result = service.simulateResource("ling1", "dbRead");

            assertTrue(result.isAllowed());
            assertEquals("ling1", result.getLingId());
            assertEquals("dbRead", result.getResourceType());
            assertNotNull(result.getTraceId());
        }

        @Test
        @DisplayName("Pipeline 拒绝调用时应返回 allowed=false")
        void shouldReturnDeniedWhenPipelineRejects() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(pipelineEngine.invoke(any(InvocationContext.class)))
                    .thenThrow(new LingInvocationException("ling1",
                            LingInvocationException.ErrorKind.SECURITY_REJECTED));

            SimulateResultDTO result = service.simulateResource("ling1", "dbRead");

            assertFalse(result.isAllowed());
            assertTrue(result.getMessage().contains("Pipeline Rejected"));
        }

        @Test
        @DisplayName("SecurityException 应返回 allowed=false")
        void shouldReturnDeniedOnSecurityException() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(pipelineEngine.invoke(any(InvocationContext.class)))
                    .thenThrow(new SecurityException("access denied"));

            SimulateResultDTO result = service.simulateResource("ling1", "dbRead");

            assertFalse(result.isAllowed());
            assertTrue(result.getMessage().contains("Access Denied"));
        }

        @Test
        @DisplayName("其他异常应返回 allowed=false")
        void shouldReturnDeniedOnGenericException() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(pipelineEngine.invoke(any(InvocationContext.class)))
                    .thenThrow(new RuntimeException("boom"));

            SimulateResultDTO result = service.simulateResource("ling1", "dbRead");

            assertFalse(result.isAllowed());
            assertTrue(result.getMessage().contains("Execution Failed"));
        }
    }

    // ==================== simulateIpc ====================

    @Nested
    @DisplayName("simulateIpc")
    class SimulateIpcTests {

        @Test
        @DisplayName("源 runtime 不存在时抛 LingNotFoundException")
        void shouldThrowWhenSourceNotFound() {
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            assertThrows(LingNotFoundException.class,
                    () -> service.simulateIpc("ling1", "ling2", true));
        }

        @Test
        @DisplayName("源 runtime 不可用时抛 LingInvocationException")
        void shouldThrowWhenSourceUnavailable() {
            LingRuntime source = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(source);
            when(source.isAvailable()).thenReturn(false);

            assertThrows(LingInvocationException.class,
                    () -> service.simulateIpc("ling1", "ling2", true));
        }

        @Test
        @DisplayName("目标 runtime 不存在时应返回 'Target ling not found'")
        void shouldReturnTargetNotFound() {
            LingRuntime source = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(source);
            when(source.isAvailable()).thenReturn(true);
            when(lingRepository.getRuntime("ling2")).thenReturn(null);

            SimulateResultDTO result = service.simulateIpc("ling1", "ling2", true);

            assertFalse(result.isAllowed());
            assertTrue(result.getMessage().contains("Target ling not found"));
        }

        @Test
        @DisplayName("目标 runtime 不可用时应返回 'Target ling not active'")
        void shouldReturnTargetNotActive() {
            LingRuntime source = mock(LingRuntime.class);
            LingRuntime target = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(source);
            when(lingRepository.getRuntime("ling2")).thenReturn(target);
            when(source.isAvailable()).thenReturn(true);
            when(target.isAvailable()).thenReturn(false);

            SimulateResultDTO result = service.simulateIpc("ling1", "ling2", true);

            assertFalse(result.isAllowed());
            assertTrue(result.getMessage().contains("Target ling not active"));
        }

        @Test
        @DisplayName("ipcEnabled=false 时应返回 'IPC authorization disabled'")
        void shouldReturnIpcDisabled() {
            LingRuntime source = mock(LingRuntime.class);
            LingRuntime target = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(source);
            when(lingRepository.getRuntime("ling2")).thenReturn(target);
            when(source.isAvailable()).thenReturn(true);
            when(target.isAvailable()).thenReturn(true);

            SimulateResultDTO result = service.simulateIpc("ling1", "ling2", false);

            assertFalse(result.isAllowed());
            assertTrue(result.getMessage().contains("IPC authorization disabled"));
        }

        @Test
        @DisplayName("Pipeline 接受 IPC 调用时应返回 allowed=true")
        void shouldReturnAllowedWhenPipelineAcceptsIpc() {
            LingRuntime source = mock(LingRuntime.class);
            LingRuntime target = mock(LingRuntime.class);
            InstancePool targetPool = mock(InstancePool.class);
            LingInstance targetInstance = mock(LingInstance.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(source);
            when(lingRepository.getRuntime("ling2")).thenReturn(target);
            when(source.isAvailable()).thenReturn(true);
            when(target.isAvailable()).thenReturn(true);
            when(target.getInstancePool()).thenReturn(targetPool);
            when(targetPool.getDefault()).thenReturn(targetInstance);
            when(pipelineEngine.invoke(any(InvocationContext.class))).thenReturn("ipc ok");

            SimulateResultDTO result = service.simulateIpc("ling1", "ling2", true);

            assertTrue(result.isAllowed());
            assertTrue(result.getMessage().contains("IPC Call Simulated Success"));
        }
    }

    // ==================== stressTest ====================

    @Nested
    @DisplayName("stressTest")
    class StressTestTests {

        @Test
        @DisplayName("runtime 不存在时抛 LingNotFoundException")
        void shouldThrowWhenRuntimeNotFound() {
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            assertThrows(LingNotFoundException.class,
                    () -> service.stressTest("ling1"));
        }

        @Test
        @DisplayName("runtime 不可用时抛 LingInvocationException")
        void shouldThrowWhenRuntimeUnavailable() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(false);

            assertThrows(LingInvocationException.class,
                    () -> service.stressTest("ling1"));
        }

        @Test
        @DisplayName("无活跃实例时抛 LingInvocationException")
        void shouldThrowWhenNoActiveInstances() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.emptyList());

            assertThrows(LingInvocationException.class,
                    () -> service.stressTest("ling1"));
        }

        @Test
        @DisplayName("canaryRouter 路由到稳定版时应返回 STABLE 结果")
        void shouldReturnStableResult() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            LingDefinition def = mock(LingDefinition.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);
            when(canaryRouter.route(anyList(), any())).thenReturn(instance);
            when(instance.getDefinition()).thenReturn(def);
            when(def.getVersion()).thenReturn("1.0.0");
            when(runtime.getActiveRequests()).thenReturn(new AtomicLong(0));

            StressResultDTO result = service.stressTest("ling1");

            assertEquals("ling1", result.getLingId());
            assertEquals(1, result.getTotalRequests());
            assertEquals(1, result.getV1Requests());
            assertEquals(0, result.getV2Requests());
            assertEquals(100, result.getV1Percent());
        }

        @Test
        @DisplayName("canaryRouter 路由到金丝雀版时应返回 CANARY 结果")
        void shouldReturnCanaryResult() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance stable = mock(LingInstance.class);
            LingInstance canary = mock(LingInstance.class);
            LingDefinition canaryDef = mock(LingDefinition.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Arrays.asList(stable, canary));
            when(pool.getDefault()).thenReturn(stable); // 默认是稳定版
            when(canaryRouter.route(anyList(), any())).thenReturn(canary); // 路由到金丝雀
            when(canary.getDefinition()).thenReturn(canaryDef);
            when(canaryDef.getVersion()).thenReturn("2.0.0");
            when(runtime.getActiveRequests()).thenReturn(new AtomicLong(5));

            StressResultDTO result = service.stressTest("ling1");

            assertEquals(1, result.getTotalRequests());
            assertEquals(0, result.getV1Requests());
            assertEquals(1, result.getV2Requests());
            assertEquals(100, result.getV2Percent());
        }

        @Test
        @DisplayName("canaryRouter 返回 null 时应回退到默认实例")
        void shouldFallbackToDefaultWhenRouterReturnsNull() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            LingDefinition def = mock(LingDefinition.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);
            when(canaryRouter.route(anyList(), any())).thenReturn(null); // 路由返回 null
            when(instance.getDefinition()).thenReturn(def);
            when(def.getVersion()).thenReturn("1.0.0");
            when(runtime.getActiveRequests()).thenReturn(new AtomicLong(0));

            StressResultDTO result = service.stressTest("ling1");

            assertNotNull(result);
            assertEquals(1, result.getTotalRequests());
        }
    }

    // ==================== simulateMethod ====================

    @Nested
    @DisplayName("simulateMethod")
    class SimulateMethodTests {

        @Test
        @DisplayName("runtime 不存在时抛 LingNotFoundException")
        void shouldThrowWhenRuntimeNotFound() {
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            assertThrows(LingNotFoundException.class,
                    () -> service.simulateMethod("ling1", "Svc", "hello", AccessType.READ));
        }

        @Test
        @DisplayName("runtime 不可用时抛 LingInvocationException")
        void shouldThrowWhenRuntimeUnavailable() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(false);

            assertThrows(LingInvocationException.class,
                    () -> service.simulateMethod("ling1", "Svc", "hello", AccessType.READ));
        }

        @Test
        @DisplayName("Pipeline 接受方法调用时应返回 allowed=true")
        void shouldReturnAllowedWhenPipelineAccepts() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(pipelineEngine.invoke(any(InvocationContext.class))).thenReturn("result");

            SimulateResultDTO result = service.simulateMethod(
                    "ling1", "com.example.Svc", "hello", AccessType.READ);

            assertTrue(result.isAllowed());
            assertEquals("ling1", result.getLingId());
            assertEquals("METHOD", result.getResourceType());
        }

        @Test
        @DisplayName("Pipeline 拒绝方法调用时应返回 allowed=false")
        void shouldReturnDeniedWhenPipelineRejects() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(pipelineEngine.invoke(any(InvocationContext.class)))
                    .thenThrow(new LingInvocationException("ling1",
                            LingInvocationException.ErrorKind.SECURITY_REJECTED));

            SimulateResultDTO result = service.simulateMethod(
                    "ling1", "com.example.Svc", "hello", AccessType.READ);

            assertFalse(result.isAllowed());
            assertTrue(result.getMessage().contains("Pipeline Rejected"));
        }
    }
}
