package com.lingframe.core.pipeline;

import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultFilterChain 测试。
 * 覆盖：链式执行、顺序保证、耗尽异常、空链。
 */
@DisplayName("DefaultFilterChain 测试")
class DefaultFilterChainTest {

    // ==================== 链式执行 ====================

    @Nested
    @DisplayName("链式执行")
    class ChainExecution {

        @Test
        @DisplayName("单过滤器链执行并返回结果")
        void singleFilterChain() throws Throwable {
            LingInvocationFilter filter = (ctx, chain) -> "hello";
            DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(filter));

            InvocationContext ctx = InvocationContext.obtain();
            assertEquals("hello", chain.doFilter(ctx));
        }

        @Test
        @DisplayName("多过滤器按顺序执行")
        void multipleFiltersInOrder() throws Throwable {
            StringBuilder sb = new StringBuilder();

            LingInvocationFilter f1 = (ctx, chain) -> { sb.append("A"); chain.doFilter(ctx); sb.append("A'"); return null; };
            LingInvocationFilter f2 = (ctx, chain) -> { sb.append("B"); chain.doFilter(ctx); sb.append("B'"); return null; };
            LingInvocationFilter f3 = (ctx, chain) -> { sb.append("C"); return "done"; };

            DefaultFilterChain chain = new DefaultFilterChain(Arrays.asList(f1, f2, f3));
            InvocationContext ctx = InvocationContext.obtain();
            chain.doFilter(ctx);

            assertEquals("ABCB'A'", sb.toString());
        }

        @Test
        @DisplayName("最后一个过滤器不调用 chain.doFilter 直接返回")
        void lastFilterReturnsDirectly() throws Throwable {
            LingInvocationFilter terminal = (ctx, chain) -> "terminal-result";
            DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(terminal));

            InvocationContext ctx = InvocationContext.obtain();
            assertEquals("terminal-result", chain.doFilter(ctx));
        }
    }

    // ==================== 链耗尽 ====================

    @Nested
    @DisplayName("链耗尽异常")
    class ChainExhausted {

        @Test
        @DisplayName("所有过滤器都调用 chain.doFilter 导致链耗尽，抛出 IllegalStateException")
        void chainExhaustedThrows() throws Throwable {
            LingInvocationFilter filter = (ctx, chain) -> chain.doFilter(ctx);
            DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(filter));

            InvocationContext ctx = InvocationContext.obtain();
            assertThrows(IllegalStateException.class, () -> chain.doFilter(ctx));
        }

        @Test
        @DisplayName("空过滤器列表立即抛出 IllegalStateException")
        void emptyFilterListThrows() throws Throwable {
            DefaultFilterChain chain = new DefaultFilterChain(Collections.emptyList());

            InvocationContext ctx = InvocationContext.obtain();
            assertThrows(IllegalStateException.class, () -> chain.doFilter(ctx));
        }
    }

    // ==================== 异常传播 ====================

    @Nested
    @DisplayName("异常传播")
    class ExceptionPropagation {

        @Test
        @DisplayName("过滤器抛出的异常正确传播")
        void exceptionPropagated() {
            RuntimeException expected = new RuntimeException("test");
            LingInvocationFilter filter = (ctx, chain) -> { throw expected; };
            DefaultFilterChain chain = new DefaultFilterChain(Collections.singletonList(filter));

            InvocationContext ctx = InvocationContext.obtain();
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> chain.doFilter(ctx));
            assertSame(expected, thrown);
        }
    }
}
