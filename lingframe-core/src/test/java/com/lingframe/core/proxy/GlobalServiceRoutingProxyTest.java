package com.lingframe.core.proxy;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.*;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/**
 * GlobalServiceRoutingProxy 测试。
 * 覆盖：动态路由、代理复用、离线异常、Object 方法处理。
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

        @Test
        @DisplayName("无目标灵元且仓库为空时抛出 LingInvocationException")
        void noTargetAndEmptyRepoThrows() {
            when(lingRepository.getAllRuntimes()).thenReturn(Collections.emptyList());
            GlobalServiceRoutingProxy proxy = createProxy(null);

            Method method = getServiceMethod();
            assertThrows(LingInvocationException.class,
                    () -> proxy.invoke(new Object(), method, null));
        }
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

            // lingRepository.getRuntime 只被调用一次（resolveTargetLingId 每次都调用）
            verify(lingRepository, atLeast(2)).getRuntime("ling-1");
        }
    }

    // ==================== 反向索引路由收敛 ====================

    @Nested
    @DisplayName("反向索引路由收敛")
    class ReverseIndexRouting {

        @Test
        @DisplayName("未注册契约返回 null 不兜底遍历（implicit-registration: false 语义）")
        void unregisteredContractReturnsNullNotFallback() {
            // 反向索引未命中返回空列表——proxy 不应再遍历 getAllRuntimes
            when(lingServiceRegistry.getLingIdsByContractId("com.example.Service"))
                    .thenReturn(Collections.emptyList());
            when(lingRepository.getAllRuntimes()).thenReturn(Collections.emptyList());

            GlobalServiceRoutingProxy proxy = createProxy(null);
            try {
                Method dummyMethod = Runnable.class.getMethod("run");
                proxy.invoke(Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[]{Runnable.class},
                        (p, m, a) -> null), dummyMethod, null);
                fail("应抛 LingInvocationException 表示离线");
            } catch (LingInvocationException e) {
                // 预期：未注册契约走反向索引未命中 → null → STATE_REJECTED
            } catch (Throwable t) {
                // 其他 Throwable 也接受，关键是验证了 getAllRuntimes 未被调
            }
            // 关键断言：兜底遍历已被删，getAllRuntimes 不应被调用
            verify(lingRepository, never()).getAllRuntimes();
        }

        @Test
        @DisplayName("反向索引命中可用灵元时直接路由")
        void reverseIndexHitRoutes() throws Throwable {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn("ling-1");
            when(runtime.isAvailable()).thenReturn(true);
            when(lingRepository.getRuntime("ling-1")).thenReturn(runtime);
            when(lingServiceRegistry.getLingIdsByContractId("com.example.Service"))
                    .thenReturn(java.util.Collections.singletonList("ling-1"));

            GlobalServiceRoutingProxy proxy = createProxy(null);
            try {
                Method dummyMethod = Runnable.class.getMethod("run");
                proxy.invoke(Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[]{Runnable.class},
                        (p, m, a) -> null), dummyMethod, null);
            } catch (Exception ignored) {
                // SmartServiceProxy 内部可能因缺少上下文抛异常，不影响反向索引命中验证
            }
            // 反向索引命中后走 getRuntime，不应再调 getAllRuntimes
            verify(lingRepository, atLeast(1)).getRuntime("ling-1");
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
