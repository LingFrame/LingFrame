package com.lingframe.dashboard.service;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.dashboard.dto.SimulateResultDTO;
import com.lingframe.dashboard.dto.StressResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    private PermissionService permissionService;
    private InvocationPipelineEngine pipelineEngine;
    private LingFrameInfo lingFrameInfo;
    private LingServiceRegistry lingServiceRegistry;
    private SimulateService service;

    @BeforeEach
    void setUp() {
        lingRepository = mock(LingRepository.class);
        eventBus = mock(EventBus.class);
        permissionService = mock(PermissionService.class);
        pipelineEngine = mock(InvocationPipelineEngine.class);
        lingFrameInfo = mock(LingFrameInfo.class);
        lingServiceRegistry = mock(LingServiceRegistry.class);
        service = new SimulateService(lingRepository, eventBus, permissionService, pipelineEngine, lingFrameInfo, lingServiceRegistry);
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
        @DisplayName("压测走 Pipeline SIMULATION 模式，应返回 STABLE 结果")
        void shouldReturnStableResultViaPipeline() {
            // 校验 stressTest 在 runtime 可用 + 单活跃实例 + 已注册契约时不抛异常，返回 DTO
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            LingDefinition def = mock(LingDefinition.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);
            when(instance.getDefinition()).thenReturn(def);
            when(def.getVersion()).thenReturn("1.0.0");
            // 灵元注册了代表契约，压测才有可路由入口
            when(lingServiceRegistry.getServicesByLingId("ling1"))
                    .thenReturn(Collections.singletonList("ling1:com.example.UserService"));

            StressResultDTO result = service.stressTest("ling1");

            assertNotNull(result);
            assertEquals("ling1", result.getLingId());
            assertEquals(1, result.getTotalRequests());
        }

        @Test
        @DisplayName("未注册任何契约时拒绝空转压测并抛 LingInvocationException")
        void shouldThrowWhenNoRegisteredContract() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(lingServiceRegistry.getServicesByLingId("ling1")).thenReturn(Collections.emptyList());

            LingInvocationException ex = assertThrows(LingInvocationException.class,
                    () -> service.stressTest("ling1"));
            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
        }

        @Test
        @DisplayName("压测上下文带裸契约 ID 且不锁定 targetLingId（全契约 L0 路由前提）")
        void shouldAssembleBareContractContextWithoutTargetLock() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);
            when(lingServiceRegistry.getServicesByLingId("ling1"))
                    .thenReturn(Collections.singletonList("ling1:com.example.UserService"));
            // 上下文在 stressTest 的 finally 中 recycle 会被清空，必须在 doAnswer 内（回收前）抓取字段
            AtomicReference<String> fqsid = new AtomicReference<>();
            AtomicReference<String> target = new AtomicReference<>();
            AtomicReference<String> caller = new AtomicReference<>();
            doAnswer(invocation -> {
                InvocationContext c = invocation.getArgument(0);
                fqsid.set(c.getServiceFQSID());
                target.set(c.getTargetLingId());
                caller.set(c.getCallerLingId());
                return null;
            }).when(pipelineEngine).invoke(any());

            service.stressTest("ling1");

            // 裸契约（无 lingId: 前缀）→ 触发 ContractProviderRoutingFilter 的 L0 provider 路由分支
            assertEquals("com.example.UserService", fqsid.get());
            // 未锁定 targetLingId → 不覆盖入口，L0 在全部候选（含灵核）间按权重选路
            assertNull(target.get());
            assertEquals("ling1", caller.get());
        }

        @Test
        @DisplayName("路由命中灵核 baseline（无版本）时按 v1 计数")
        void shouldCountCoreHitAsV1() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);
            when(lingServiceRegistry.getServicesByLingId("ling1"))
                    .thenReturn(Collections.singletonList("ling1:com.example.UserService"));
            // 模拟 L0 路由结果：命中灵核 provider（无版本）
            doAnswer(invocation -> {
                InvocationContext c = invocation.getArgument(0);
                c.setTargetLingId(LingCoreConstants.LINGCORE_LING_ID);
                return null;
            }).when(pipelineEngine).invoke(any());

            StressResultDTO result = service.stressTest("ling1");

            assertEquals(1, result.getV1Requests());
            assertEquals(0, result.getV2Requests());
        }

        @Test
        @DisplayName("路由命中非默认版本 provider 时按 v2 计数")
        void shouldCountNonDefaultVersionAsV2() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            LingDefinition def = mock(LingDefinition.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);
            when(instance.getDefinition()).thenReturn(def);
            when(def.getVersion()).thenReturn("1.0.0");
            when(lingServiceRegistry.getServicesByLingId("ling1"))
                    .thenReturn(Collections.singletonList("ling1:com.example.UserService"));
            // 模拟 L0 路由结果：命中灵元非默认版本 provider
            doAnswer(invocation -> {
                InvocationContext c = invocation.getArgument(0);
                c.setTargetLingId("ling1");
                c.setTargetVersion("1.1.0");
                return null;
            }).when(pipelineEngine).invoke(any());

            StressResultDTO result = service.stressTest("ling1");

            assertEquals(0, result.getV1Requests());
            assertEquals(1, result.getV2Requests());
        }

        @Test
        @DisplayName("路由命中默认版本 provider 时按 v1 计数")
        void shouldCountDefaultVersionAsV1() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInstance instance = mock(LingInstance.class);
            LingDefinition def = mock(LingDefinition.class);

            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);
            when(instance.getDefinition()).thenReturn(def);
            when(def.getVersion()).thenReturn("1.0.0");
            when(lingServiceRegistry.getServicesByLingId("ling1"))
                    .thenReturn(Collections.singletonList("ling1:com.example.UserService"));
            // 模拟 L0 路由结果：命中灵元默认版本 provider
            doAnswer(invocation -> {
                InvocationContext c = invocation.getArgument(0);
                c.setTargetLingId("ling1");
                c.setTargetVersion("1.0.0");
                return null;
            }).when(pipelineEngine).invoke(any());

            StressResultDTO result = service.stressTest("ling1");

            assertEquals(1, result.getV1Requests());
            assertEquals(0, result.getV2Requests());
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

    // ==================== InvocationContext 池配对回收 ====================

    @Nested
    @DisplayName("InvocationContext 池配对回收（obtain/recycle）")
    class InvocationContextRecycleTests {

        @BeforeEach
        void setUpRuntime() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(lingRepository.getRuntime("target")).thenReturn(runtime);
            when(runtime.isAvailable()).thenReturn(true);
            InstancePool pool = mock(InstancePool.class);
            when(runtime.getInstancePool()).thenReturn(pool);
            LingInstance instance = mock(LingInstance.class);
            when(pool.getDefault()).thenReturn(instance);
            when(instance.getVersion()).thenReturn("1.0");
            when(instance.getClassLoader()).thenReturn(getClass().getClassLoader());
            when(pipelineEngine.invoke(any(InvocationContext.class))).thenReturn("ok");
        }

        @Test
        @DisplayName("simulateResource 调用后应把上下文回收回线程池（池大小不变）")
        void simulateResourceShouldRecycleContext() throws Exception {
            int before = primeInvocationContextPool(2);
            service.simulateResource("ling1", "dbRead");
            assertEquals(before, invocationContextPoolSize(),
                    "simulateResource 必须配对 recycle()，否则对象池会泄漏上下文");
            assertNull(InvocationContext.current(), "调用后不应残留 ThreadLocal 活动上下文");
        }

        @Test
        @DisplayName("simulateIpc 调用后应把上下文回收回线程池（池大小不变）")
        void simulateIpcShouldRecycleContext() throws Exception {
            int before = primeInvocationContextPool(2);
            service.simulateIpc("ling1", "target", true);
            assertEquals(before, invocationContextPoolSize(),
                    "simulateIpc 必须配对 recycle()，否则对象池会泄漏上下文");
            assertNull(InvocationContext.current(), "调用后不应残留 ThreadLocal 活动上下文");
        }

        @Test
        @DisplayName("simulateMethod 调用后应把上下文回收回线程池（池大小不变）")
        void simulateMethodShouldRecycleContext() throws Exception {
            int before = primeInvocationContextPool(2);
            service.simulateMethod("ling1", "com.example.Svc", "hello", AccessType.READ);
            assertEquals(before, invocationContextPoolSize(),
                    "simulateMethod 必须配对 recycle()，否则对象池会泄漏上下文");
            assertNull(InvocationContext.current(), "调用后不应残留 ThreadLocal 活动上下文");
        }

        @Test
        @DisplayName("simulateIpc 目标灵元不存在时应不触碰对象池（池大小不变）")
        void simulateIpcWithoutTargetShouldNotTouchPool() throws Exception {
            when(lingRepository.getRuntime("target")).thenReturn(null);
            int before = primeInvocationContextPool(2);
            service.simulateIpc("ling1", "target", true);
            assertEquals(before, invocationContextPoolSize());
        }
    }

    /** 向当前线程的对象池预热 n 个可回收上下文，返回预热后的池大小 */
    private static int primeInvocationContextPool(int n) throws Exception {
        List<InvocationContext> tmp = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            tmp.add(InvocationContext.obtain());
        }
        tmp.forEach(InvocationContext::recycle);
        return invocationContextPoolSize();
    }

    @SuppressWarnings("unchecked")
    private static int invocationContextPoolSize() throws Exception {
        java.lang.reflect.Field stackField = InvocationContext.class.getDeclaredField("STACK");
        stackField.setAccessible(true);
        ThreadLocal<java.util.Deque<InvocationContext>> tl =
                (ThreadLocal<java.util.Deque<InvocationContext>>) stackField.get(null);
        return tl.get().size();
    }
}
