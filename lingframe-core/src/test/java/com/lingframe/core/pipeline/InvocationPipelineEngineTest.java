package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * InvocationPipelineEngine 测试。
 * 覆盖：invoke 流程、异常包装、资源驱逐、治理恢复。
 */
@DisplayName("InvocationPipelineEngine 测试")
class InvocationPipelineEngineTest {

    private FilterRegistry registry;
    private InvocationPipelineEngine engine;

    @BeforeEach
    void setUp() {
        registry = mock(FilterRegistry.class);
        engine = new InvocationPipelineEngine(registry);
    }

    // ==================== invoke 流程 ====================

    @Nested
    @DisplayName("invoke 流程")
    class InvokeFlow {

        @Test
        @DisplayName("invoke 执行过滤器链并返回结果")
        void invokeExecutesChain() throws Throwable {
            when(registry.getOrderedFilters()).thenReturn(java.util.Collections.singletonList(
                    (LingInvocationFilter) (ctx, chain) -> "result"
            ));

            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            Object result = engine.invoke(ctx);
            assertEquals("result", result);
        }

        @Test
        @DisplayName("invoke 正确挂载和恢复 InvocationContext")
        void invokeAttachesAndDetachesContext() throws Throwable {
            when(registry.getOrderedFilters()).thenReturn(java.util.Collections.singletonList(
                    (LingInvocationFilter) (ctx, chain) -> {
                        // 执行期间 current 应该可用
                        assertNotNull(InvocationContext.current());
                        return "ok";
                    }
            ));

            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            engine.invoke(ctx);

            // 执行完毕后 current 应该恢复
            assertNull(InvocationContext.current());
        }
    }

    // ==================== 异常包装 ====================

    @Nested
    @DisplayName("异常包装")
    class ExceptionWrapping {

        @Test
        @DisplayName("LingInvocationException 直接传播")
        void lingExceptionPropagatedDirectly() throws Throwable {
            LingInvocationException expected = new LingInvocationException(
                    "ling-1:Service", LingInvocationException.ErrorKind.SECURITY_REJECTED);

            when(registry.getOrderedFilters()).thenReturn(java.util.Collections.singletonList(
                    (LingInvocationFilter) (ctx, chain) -> { throw expected; }
            ));

            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            LingInvocationException thrown = assertThrows(LingInvocationException.class,
                    () -> engine.invoke(ctx));
            assertSame(expected, thrown);
        }

        @Test
        @DisplayName("非 LingInvocationException 包装为 INTERNAL_ERROR")
        void otherExceptionWrappedAsInternalError() throws Throwable {
            RuntimeException cause = new RuntimeException("unexpected");

            when(registry.getOrderedFilters()).thenReturn(java.util.Collections.singletonList(
                    (LingInvocationFilter) (ctx, chain) -> { throw cause; }
            ));

            InvocationContext ctx = InvocationContext.obtain();
            ctx.setServiceFQSID("ling-1:Service");

            LingInvocationException thrown = assertThrows(LingInvocationException.class,
                    () -> engine.invoke(ctx));
            assertEquals(LingInvocationException.ErrorKind.INTERNAL_ERROR, thrown.getKind());
            assertSame(cause, thrown.getCause());
        }
    }

    // ==================== 资源驱逐 ====================

    @Nested
    @DisplayName("资源驱逐")
    class ResourceEviction {

        @Test
        @DisplayName("evictLingResources 委托给 FilterRegistry")
        void evictLingResourcesDelegates() {
            engine.evictLingResources("ling-1");
            verify(registry).evictLingResources("ling-1");
        }

        @Test
        @DisplayName("evictMethodCache 委托给 FilterRegistry")
        void evictMethodCacheDelegates() {
            when(registry.evictMethodCache("ling-1")).thenReturn(3);
            int evicted = engine.evictMethodCache("ling-1");
            assertEquals(3, evicted);
            verify(registry).evictMethodCache("ling-1");
        }
    }

    // ==================== 治理恢复 ====================

    @Nested
    @DisplayName("治理恢复")
    class GovernanceRecovery {

        @Test
        @DisplayName("recoverLingGovernance 委托给 FilterRegistry")
        void recoverDelegates() {
            when(registry.recoverLingGovernance("ling-1")).thenReturn(true);
            assertTrue(engine.recoverLingGovernance("ling-1"));
            verify(registry).recoverLingGovernance("ling-1");
        }

        @Test
        @DisplayName("registry 为 null 时 recoverLingGovernance 返回 false")
        void nullRegistryReturnsFalse() {
            InvocationPipelineEngine eng = new InvocationPipelineEngine(null);
            assertFalse(eng.recoverLingGovernance("ling-1"));
        }
    }
}
