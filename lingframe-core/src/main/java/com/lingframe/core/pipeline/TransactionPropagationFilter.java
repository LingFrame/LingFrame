package com.lingframe.core.pipeline;

import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionRollbackException;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.TransactionBindingHook;

import java.sql.Connection;

/**
 * 事务上下文穿透过滤器。
 * <p>
 * 位置：{@link FilterPhase#TRANSACTION_PROPAGATION}（=250，ROUTING 之后、
 * RESOLUTION 类加载器切换之前，POLICY_PREFILL 与 RESILIENCE 之间）。
 * <p>
 * 职责：把上游活跃事务的物理连接【按 dataSourceId】推入 {@link LingTransactionContext}，
 * 供下游灵元经受管数据源代理按自身 id 精确查栈复用；调用返回后回传 rollbackOnly 信号
 * 并擦除上下文。
 * <p>
 * 线程边界：本过滤器只负责【主线程】的 push / 信号回传 / finally 擦除；
 * {@link ThreadIsolationGovernanceFilter}（EXECUTION_ISOLATION）会把连接快照随任务搬运到
 * worker 线程，两者协同才能实现真正的跨线程穿透。
 * <p>
 * 执行模式门控：仅 NORMAL 模式穿透——SIMULATION 终端只做模拟（无真实副作用）、
 * GOVERN_ONLY 不进终端调用（push 的连接无消费者），两者一律直接放行。
 * <p>
 * 穿透总开关：{@code propagationEnabled=false} 时直接放行（不压栈），配套的灵元侧
 * 受管事务管理器亦不注册，穿透链路整体不激活（应急降级路径）。
 */
public class TransactionPropagationFilter implements LingInvocationFilter {

    private final TransactionBindingHook transactionBindingHook;
    private final boolean propagationEnabled;

    public TransactionPropagationFilter(TransactionBindingHook transactionBindingHook) {
        this(transactionBindingHook, true);
    }

    public TransactionPropagationFilter(TransactionBindingHook transactionBindingHook, boolean propagationEnabled) {
        this.transactionBindingHook = transactionBindingHook;
        this.propagationEnabled = propagationEnabled;
    }

    @Override
    public int getOrder() {
        return FilterPhase.TRANSACTION_PROPAGATION;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // 执行模式门控：仅 NORMAL 穿透；SIMULATION / GOVERN_ONLY 直接放行
        if (ctx.execution().getMode() != InvocationExecutionMode.NORMAL) {
            return chain.doFilter(ctx);
        }
        // 穿透总开关：关闭时直接放行（不压栈），穿透链路整体不激活
        if (!propagationEnabled) {
            return chain.doFilter(ctx);
        }

        int pushed = 0;

        // 通过 SPI 检查当前线程是否有活跃事务（core 不直接触碰 Spring）；
        // 按 hook 报告的活跃绑定源集合逐源压栈（模式 1 恒为 {"default"}）
        if (transactionBindingHook != null && transactionBindingHook.isTransactionActive()) {
            for (String dataSourceId : transactionBindingHook.getActiveBoundDataSourceIds()) {
                Connection conn = transactionBindingHook.getBoundConnection(dataSourceId);
                if (conn != null && !conn.isClosed()) {
                    LingTransactionContext.pushConnection(dataSourceId, conn);
                    pushed++;
                }
            }
        }

        try {
            // 跨边界执行调用（TCCL 切换 / worker 搬运 -> 灵元执行 Mapper SQL，复用该 Connection）
            Object result = chain.doFilter(ctx);

            // rollbackOnly 信号回传：不再要求本层 push 过才检查——嵌套调用中本层可能未
            // push（栈由更上层维护）但下游信号已合并至本线程上下文，门控检查会漏判。
            // 栈非空或标志置位即检查，宁可误抛（触发回滚）不可漏判（静默提交）
            if (pushed > 0 || LingTransactionContext.hasAnyConnection()) {
                if (LingTransactionContext.isRollbackOnly()) {
                    throw new LingTransactionRollbackException(
                            "Downstream ling marked transaction as rollbackOnly, triggering upstream rollback");
                }
            }

            return result;
        } finally {
            // 防泄漏核心护栏：调用返回后，强制擦除本层压入的连接指针（逐源弹栈）
            for (int i = 0; i < pushed; i++) {
                LingTransactionContext.popConnection();
            }
            LingTransactionContext.cleanIfEmpty();
        }
    }
}
