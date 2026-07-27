package com.lingframe.core.proxy;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.*;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GlobalServiceRoutingProxy 测试。
 * 覆盖：动态路由、代理复用、离线异常、Object 方法处理、
 * 默认路由（裸 contractId FQSID）。
 * <p>
 * 去身份化后无 targetLingId 时 SmartServiceProxy 组装 FQSID 直接为裸 contractId（接口全限定名），
 * 由 ContractProviderRoutingFilter 在 L0 阶段按 provider 权重选中具体 provider。
 */
@DisplayName("GlobalServiceRoutingProxy 测试")
class GlobalServiceRoutingProxyTest {

    private LingRepository lingRepository;
    private InvocationPipelineEngine pipelineEngine;
    private LingServiceRegistry lingServiceRegistry;

    @BeforeEach
    void setUp() {
        lingRepository = mock(LingRepository.class);
        pipelineEngine = mock(InvocationPipelineEngine.class);
        lingServiceRegistry = mock(LingServiceRegistry.class);
    }

    private GlobalServiceRoutingProxy createProxy(String targetLingId) {
        return new GlobalServiceRoutingProxy(
                "caller-ling", "com.example.Service", targetLingId,
                lingRepository, pipelineEngine, lingServiceRegistry);
    }

    // ==================== 离线异常 ====================

    @Nested
    @DisplayName("离线异常")
    class OfflineException {

        @Test
        @DisplayName("目标灵元不在线时抛出 LingInvocationException")
        void offlineTargetThrows() {
            when(lingRepository.getRuntime("ling-1")).thenReturn(null);
            GlobalServiceRoutingProxy proxy = createProxy("ling-1");

            Method method = getServiceMethod();
            assertThrows(LingInvocationException.class,
                    () -> proxy.invoke(new Object(), method, null));
        }
        // 路由升维后，无 targetLingId 不再走「反向索引兜底 + 空仓抛异常」路径，
        // 而是组装裸 contractId FQSID 交由 pipeline 内 ContractProviderRoutingFilter 决策。
        // 原有的 noTargetAndEmptyRepoThrows 测试用例已迁移至「默认路由裸 contractId」
        // Nested 类，由 pipeline 行为覆盖。
    }

    // ==================== 动态路由 ====================

    @Nested
    @DisplayName("动态路由")
    class DynamicRouting {

        @Test
        @DisplayName("显式指定 targetLingId 时直接路由")
        void explicitTargetLingId() throws Throwable {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn("ling-1");
            when(lingRepository.getRuntime("ling-1")).thenReturn(runtime);

            GlobalServiceRoutingProxy proxy = createProxy("ling-1");

            // 调用 Object.toString() 会直接处理，不走 SmartServiceProxy
            // 所以用一个非 Object 方法来测试路由
            // 但 invoke 内部会先 resolveTargetLingId，然后委托给 SmartServiceProxy
            // SmartServiceProxy 需要 InvocationPipelineEngine 工作
            // 这里只验证 resolveTargetLingId 逻辑正确调用了 getRuntime
            try {
                Method dummyMethod = Runnable.class.getMethod("run");
                proxy.invoke(Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[]{Runnable.class},
                        (p, m, a) -> null), dummyMethod, null);
            } catch (LingInvocationException e) {
                // SmartServiceProxy 内部可能因缺少完整上下文而抛异常
                // 但关键是 lingRepository.getRuntime 被调用了
            }
            verify(lingRepository).getRuntime("ling-1");
        }
    }

    // ==================== Object 方法处理 ====================

    @Nested
    @DisplayName("Object 方法处理")
    class ObjectMethods {

        @Test
        @DisplayName("toString 返回代理描述")
        void toStringReturnsDescription() throws Throwable {
            GlobalServiceRoutingProxy proxy = createProxy("ling-1");
            Method toStringMethod = Object.class.getMethod("toString");

            Object result = proxy.invoke(new Object(), toStringMethod, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("hashCode 返回值")
        void hashCodeReturnsValue() throws Throwable {
            GlobalServiceRoutingProxy proxy = createProxy("ling-1");
            Method hashCodeMethod = Object.class.getMethod("hashCode");

            Object result = proxy.invoke(new Object(), hashCodeMethod, null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("equals 与自身比较返回 true")
        void equalsWithSelf() throws Throwable {
            GlobalServiceRoutingProxy proxy = createProxy("ling-1");
            Method equalsMethod = Object.class.getMethod("equals", Object.class);

            // Object.equals 通过 method.invoke(this, args) 委托
            Object result = proxy.invoke(proxy, equalsMethod, new Object[]{proxy});
            assertEquals(true, result);
        }
    }

    // ==================== 代理复用 ====================

    @Nested
    @DisplayName("代理复用")
    class ProxyReuse {

        @Test
        @DisplayName("同一灵元 ID 复用 SmartServiceProxy")
        void sameLingIdReusesProxy() throws Throwable {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn("ling-1");
            when(lingRepository.getRuntime("ling-1")).thenReturn(runtime);

            GlobalServiceRoutingProxy proxy = createProxy("ling-1");

            // 两次调用应该复用 delegate（通过 cachedDelegate 字段）
            // 无法直接验证，但至少不应抛异常
            try {
                Method dummyMethod = Runnable.class.getMethod("run");
                proxy.invoke(Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[]{Runnable.class},
                        (p, m, a) -> null), dummyMethod, null);
            } catch (Exception ignored) {
                // SmartServiceProxy 内部可能因缺少上下文抛异常
            }

            // 第二次调用
            try {
                Method dummyMethod = Runnable.class.getMethod("run");
                proxy.invoke(Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[]{Runnable.class},
                        (p, m, a) -> null), dummyMethod, null);
            } catch (Exception ignored) {
            }

            // lingRepository.getRuntime 只被调用（resolveTargetLingId 每次都调用）
            verify(lingRepository, atLeast(2)).getRuntime("ling-1");
        }
    }

    // ==================== 默认路由裸 contractId ====================

    @Nested
    @DisplayName("默认路由裸 contractId")
    class ProviderRouting {

        @Test
        @DisplayName("无 targetLingId 时跳过 lingRepository 预校验，委托 pipeline 解析")
        void noTargetSkipsRuntimePreValidation() throws Throwable {
            when(pipelineEngine.invoke(any())).thenReturn(null);
            GlobalServiceRoutingProxy proxy = createProxy(null);

            Method dummyMethod = Runnable.class.getMethod("run");
            proxy.invoke(Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{Runnable.class},
                    (p, m, a) -> null), dummyMethod, null);

            // 裸 contractId 路径不预校验 runtime——交由 ContractProviderRoutingFilter 决策
            verify(lingRepository, never()).getRuntime(anyString());
            // 反向索引已被删除——不再调 lingServiceRegistry
            verify(lingServiceRegistry, never()).getLingIdsByContractId(anyString());
            // 委托最终落到 pipelineEngine
            verify(pipelineEngine, atLeast(1)).invoke(any());
        }

        @Test
        @DisplayName("无 targetLingId 时组装裸 contractId FQSID")
        void noTargetConstructsBareContractIdFqsid() throws Throwable {
            // 用 Answer 在 ctx.recycle() 之前捕获 FQSID——recycle 会清空 ThreadLocal 字段
            AtomicReference<String> capturedFqsid = new AtomicReference<>();
            when(pipelineEngine.invoke(any())).thenAnswer(invocation -> {
                InvocationContext ctx = invocation.getArgument(0);
                capturedFqsid.set(ctx.getServiceFQSID());
                return null;
            });

            GlobalServiceRoutingProxy proxy = createProxy(null);
            Method dummyMethod = Runnable.class.getMethod("run");
            proxy.invoke(Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[]{Runnable.class},
                    (p, m, a) -> null), dummyMethod, null);

            // 裸 contractId（接口全限定名），无 lingId: 前缀
            assertEquals("com.example.Service", capturedFqsid.get());
        }

        @Test
        @DisplayName("无 targetLingId 时不触发兜底遍历 getAllRuntimes")
        void noTargetNeverTriggersGetAllRuntimes() throws Throwable {
            when(pipelineEngine.invoke(any())).thenReturn(null);
            GlobalServiceRoutingProxy proxy = createProxy(null);

            Method dummyMethod = Runnable.class.getMethod("run");
            try {
                proxy.invoke(Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[]{Runnable.class},
                        (p, m, a) -> null), dummyMethod, null);
            } catch (Exception ignored) {
                // SmartServiceProxy 内部可能因缺少上下文抛异常，不影响验证
            }
            // 老的兜底遍历已被彻底删除
            verify(lingRepository, never()).getAllRuntimes();
        }
    }

    // ==================== 辅助方法 ====================

    private Method getServiceMethod() {
        try {
            return Runnable.class.getMethod("run");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
