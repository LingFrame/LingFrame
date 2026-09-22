package com.lingframe.core.pipeline;

import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionContext.TransactionSnapshot;
import com.lingframe.core.spi.ThreadLocalPropagator;

/**
 * 事务上下文跨线程搬运传播器。
 * <p>
 * 实现 core.spi 既有 {@link ThreadLocalPropagator} 契约，内部委托 api 的
 * {@link LingTransactionContext} 快照方法完成连接（下行）与 rollbackOnly 信号（上行）
 * 的双向搬运——不改动 SPI 三方法签名，信号上行通过快照对象自身的 volatile 字段承载。
 * <p>
 * 三阶段语义：
 * <ul>
 *   <li><b>capture</b>（主线程）：捕获当前线程事务上下文快照（各源栈顶连接 + rollbackOnly），
 *       该快照同时充当下行载体与上行载体；</li>
 *   <li><b>replay</b>（worker 线程）：重放快照，把下行连接带入 worker 线程的穿透上下文，
 *       并记录 worker 执行前状态供恢复；</li>
 *   <li><b>restore</b>（worker finally）：合并语义——先把 worker 执行期间置位的 rollbackOnly
 *       并入快照（上行），再把 worker 线程恢复为执行前状态（擦除资源、保留信号）。</li>
 * </ul>
 * <p>
 * <b>按调用实例化</b>：本传播器在 replay 时持有 worker 执行前状态（合并恢复所需），
 * 属每次调用的实例态；由调用方（ThreadIsolationGovernanceFilter）每次 doFilter 创建
 * 新实例，而非作为共享单例注入——避免跨并发请求的字段串扰。
 */
public class TransactionContextPropagator implements ThreadLocalPropagator<TransactionSnapshot> {

    /** worker 线程执行前的事务上下文状态（replay 记录，restore 恢复用） */
    private TransactionSnapshot previous;

    @Override
    public TransactionSnapshot capture() {
        return LingTransactionContext.captureSnapshot();
    }

    @Override
    public TransactionSnapshot replay(TransactionSnapshot snapshot) {
        // 下行：worker 线程重放快照（连接带入），返回执行前状态供恢复
        this.previous = LingTransactionContext.applySnapshot(snapshot);
        return this.previous;
    }

    @Override
    public void restore(TransactionSnapshot carrier) {
        // 合并语义：worker 期间置位的 rollbackOnly 并入 carrier（上行），
        // 再恢复 worker 线程为执行前状态；随后释放状态引用
        LingTransactionContext.restoreSnapshot(this.previous, carrier);
        this.previous = null;
    }
}
