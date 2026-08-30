package com.lingframe.core.pipeline;

import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionRollbackException;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.TransactionBindingHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事务上下文穿透过滤器测试：穿透压栈 / 执行模式门控 / hook 判空降级 / rollbackOnly 信号回传 /
 * finally 双端擦除。
 */
@DisplayName("TransactionPropagationFilter 事务穿透过滤器")
class TransactionPropagationFilterTest {

    private static final String DATA_SOURCE_ID = "default";

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    private InvocationContext normalContext() {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.attach();
        ctx.execution().setMode(InvocationExecutionMode.NORMAL);
        return ctx;
    }

    private InvocationContext contextWithMode(InvocationExecutionMode mode) {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.attach();
        ctx.execution().setMode(mode);
        return ctx;
    }

    private static TransactionBindingHook activeHook(Connection conn) {
        TransactionBindingHook hook = mock(TransactionBindingHook.class);
        when(hook.isTransactionActive()).thenReturn(true);
        when(hook.getActiveBoundDataSourceIds()).thenReturn(Collections.singleton(DATA_SOURCE_ID));
        when(hook.getBoundConnection(DATA_SOURCE_ID)).thenReturn(conn);
        return hook;
    }

    private static TransactionBindingHook inactiveHook() {
        TransactionBindingHook hook = mock(TransactionBindingHook.class);
        when(hook.isTransactionActive()).thenReturn(false);
        when(hook.getActiveBoundDataSourceIds()).thenReturn(Collections.<String>emptySet());
        return hook;
    }

    @Nested
    @DisplayName("穿透压栈与 finally 擦除")
    class PropagationAndCleanup {

        @Test
        @DisplayName("活跃事务存在时按源压栈，链内可复用穿透连接")
        void pushesConnectionWhenTransactionActive() throws Throwable {
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            TransactionBindingHook hook = activeHook(conn);
            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenAnswer(invocation -> {
                // 链内（下游灵元执行时）能拿到穿透连接
                assertSame(conn, LingTransactionContext.getCurrentConnection(DATA_SOURCE_ID));
                return "ok";
            });

            Object result = filter.doFilter(ctx, chain);

            assertSame("ok", result);
            verify(hook).getBoundConnection(DATA_SOURCE_ID);
            // finally 擦除：调用返回后栈空
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }

        @Test
        @DisplayName("无活跃事务时链内无穿透连接，且不触碰 hook 的连接提取")
        void noPushWhenTransactionInactive() throws Throwable {
            TransactionBindingHook hook = inactiveHook();
            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenAnswer(invocation -> {
                assertFalse(LingTransactionContext.hasAnyConnection());
                return "ok";
            });

            filter.doFilter(ctx, chain);

            verify(hook, never()).getBoundConnection(DATA_SOURCE_ID);
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }

        @Test
        @DisplayName("hook 为 null 时降级为无穿透（纯 core/native 场景），不抛错")
        void degradesWhenHookNull() throws Throwable {
            TransactionPropagationFilter filter = new TransactionPropagationFilter(null);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenReturn("ok");

            Object result = filter.doFilter(ctx, chain);

            assertSame("ok", result);
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }
    }

    @Nested
    @DisplayName("执行模式门控")
    class ModeGating {

        @Test
        @DisplayName("SIMULATION 模式直接放行，不压栈（终端只做模拟）")
        void simulationPassesThrough() throws Throwable {
            Connection conn = mock(Connection.class);
            TransactionBindingHook hook = activeHook(conn);
            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = contextWithMode(InvocationExecutionMode.SIMULATION);

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenReturn("sim");

            assertSame("sim", filter.doFilter(ctx, chain));

            verify(hook, never()).getBoundConnection(DATA_SOURCE_ID);
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }

        @Test
        @DisplayName("GOVERN_ONLY 模式直接放行，不压栈（不进终端，push 无消费者）")
        void governOnlyPassesThrough() throws Throwable {
            Connection conn = mock(Connection.class);
            TransactionBindingHook hook = activeHook(conn);
            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = contextWithMode(InvocationExecutionMode.GOVERN_ONLY);

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenReturn("gov");

            assertSame("gov", filter.doFilter(ctx, chain));

            verify(hook, never()).getBoundConnection(DATA_SOURCE_ID);
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }

        @Test
        @DisplayName("穿透总开关关闭时直接放行，不压栈（应急降级路径）")
        void disabledPropagationPassesThrough() throws Throwable {
            Connection conn = mock(Connection.class);
            TransactionBindingHook hook = activeHook(conn);
            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook, false);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenReturn("disabled");

            assertSame("disabled", filter.doFilter(ctx, chain));

            verify(hook, never()).getBoundConnection(DATA_SOURCE_ID);
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }
    }

    @Nested
    @DisplayName("rollbackOnly 信号回传")
    class RollbackOnlySignal {

        @Test
        @DisplayName("链内下游置位 rollbackOnly → 过滤器抛 LingTransactionRollbackException 触发上游回滚")
        void throwsWhenDownstreamMarksRollbackOnly() throws Throwable {
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            TransactionBindingHook hook = activeHook(conn);
            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenAnswer(invocation -> {
                // 模拟下游 NonCloseableLingConnectionProxy.rollback 仅置信号
                LingTransactionContext.setRollbackOnly();
                return "ok";   // 正常返回（吞异常场景）
            });

            assertThrows(LingTransactionRollbackException.class, () -> filter.doFilter(ctx, chain));
            // finally 擦除：异常路径下连接也归还
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }

        @Test
        @DisplayName("链内未置位 rollbackOnly → 正常返回不抛异常")
        void noExceptionWhenNoRollbackSignal() throws Throwable {
            Connection conn = mock(Connection.class);
            when(conn.isClosed()).thenReturn(false);
            TransactionBindingHook hook = activeHook(conn);
            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenReturn("ok");

            assertSame("ok", filter.doFilter(ctx, chain));
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }
    }

    @Nested
    @DisplayName("多源压栈")
    class MultiSource {

        @Test
        @DisplayName("多个活跃绑定源逐源压栈，链内按源可分别取到")
        void pushesAllActiveSources() throws Throwable {
            Connection orderConn = mock(Connection.class);
            Connection userConn = mock(Connection.class);
            when(orderConn.isClosed()).thenReturn(false);
            when(userConn.isClosed()).thenReturn(false);

            TransactionBindingHook hook = mock(TransactionBindingHook.class);
            when(hook.isTransactionActive()).thenReturn(true);
            Set<String> sources = new LinkedHashSet<>();
            sources.add("order-ds");
            sources.add("user-ds");
            when(hook.getActiveBoundDataSourceIds()).thenReturn(sources);
            when(hook.getBoundConnection("order-ds")).thenReturn(orderConn);
            when(hook.getBoundConnection("user-ds")).thenReturn(userConn);

            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenAnswer(invocation -> {
                assertSame(orderConn, LingTransactionContext.getCurrentConnection("order-ds"));
                assertSame(userConn, LingTransactionContext.getCurrentConnection("user-ds"));
                return "ok";
            });

            assertSame("ok", filter.doFilter(ctx, chain));
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }

        @Test
        @DisplayName("多源逐源弹栈配对：finally 后各源栈均清空（无参 pop 与压入顺序严格配对）")
        void popsAllSourcesOnFinally() throws Throwable {
            Connection orderConn = mock(Connection.class);
            Connection userConn = mock(Connection.class);
            when(orderConn.isClosed()).thenReturn(false);
            when(userConn.isClosed()).thenReturn(false);

            TransactionBindingHook hook = mock(TransactionBindingHook.class);
            when(hook.isTransactionActive()).thenReturn(true);
            Set<String> sources = new LinkedHashSet<>();
            sources.add("order-ds");
            sources.add("user-ds");
            when(hook.getActiveBoundDataSourceIds()).thenReturn(sources);
            when(hook.getBoundConnection("order-ds")).thenReturn(orderConn);
            when(hook.getBoundConnection("user-ds")).thenReturn(userConn);

            TransactionPropagationFilter filter = new TransactionPropagationFilter(hook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenThrow(new RuntimeException("boom"));

            // 异常路径：finally 逐源弹栈 + cleanIfEmpty 仍要清空全部源
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> filter.doFilter(ctx, chain));
            assertFalse(LingTransactionContext.hasAnyConnection());
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }
    }

    @Nested
    @DisplayName("嵌套调用栈深恒定")
    class NestedStackDepth {

        @Test
        @DisplayName("嵌套层（hook 判定无活跃事务）不重复压栈，栈由最外层维护")
        void nestedLayerDoesNotPush() throws Throwable {
            // 最外层：活跃事务 + 单源，压栈一次
            Connection outerConn = mock(Connection.class);
            when(outerConn.isClosed()).thenReturn(false);
            TransactionBindingHook outerHook = activeHook(outerConn);

            // 嵌套层：hook 判定无活跃事务（灵元侧 TSM 未激活），不压栈
            TransactionBindingHook nestedHook = inactiveHook();

            // 模拟外层已压栈：直接预置连接进上下文（等效最外层 Filter 压栈后的状态）
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, outerConn);

            TransactionPropagationFilter nestedFilter = new TransactionPropagationFilter(nestedHook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenAnswer(invocation -> {
                // 嵌套层执行期间：连接仍在栈中（被外层维护），未被嵌套层重复压栈或误清
                assertSame(outerConn, LingTransactionContext.getCurrentConnection(DATA_SOURCE_ID));
                assertFalse(LingTransactionContext.isRollbackOnly());
                return "ok";
            });

            assertSame("ok", nestedFilter.doFilter(ctx, chain));

            // 嵌套层 finally 不清外层连接（栈非空时 cleanIfEmpty 不清理）
            assertSame(outerConn, LingTransactionContext.getCurrentConnection(DATA_SOURCE_ID));
            // 清理测试残留：弹出外层连接
            LingTransactionContext.popConnection();
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }

        @Test
        @DisplayName("嵌套层下游置位 rollbackOnly → 嵌套层无门控检查命中并抛异常（逐层加速失败）")
        void nestedLayerDetectsRollbackSignal() throws Throwable {
            Connection outerConn = mock(Connection.class);
            when(outerConn.isClosed()).thenReturn(false);
            LingTransactionContext.pushConnection(DATA_SOURCE_ID, outerConn);

            TransactionBindingHook nestedHook = inactiveHook();
            TransactionPropagationFilter nestedFilter = new TransactionPropagationFilter(nestedHook);
            InvocationContext ctx = normalContext();

            LingFilterChain chain = mock(LingFilterChain.class);
            when(chain.doFilter(ctx)).thenAnswer(invocation -> {
                // 嵌套层内的下游（更内层灵元）声明回滚
                LingTransactionContext.setRollbackOnly();
                return "ok";
            });

            // 嵌套层虽未 push，但栈非空（外层维护）→ 无门控检查命中 rollbackOnly → 抛异常
            org.junit.jupiter.api.Assertions.assertThrows(LingTransactionRollbackException.class,
                    () -> nestedFilter.doFilter(ctx, chain));

            LingTransactionContext.popConnection();
            assertFalse(LingTransactionContext.hasAnyConnection());
            InvocationContext.detach(null);
        }
    }
}
