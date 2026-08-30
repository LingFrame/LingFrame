package com.lingframe.api.storage;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用链事务穿透上下文。
 * <p>
 * 在调用链存活期内持有穿透 {@link Connection}（按 dataSourceId 分栈的线程局部存储），
 * 以及全局 rollbackOnly 回滚信号。纯 JDK 类型，不依赖任何 Spring / core 类型——
 * 被 core 侧 {@code TransactionPropagationFilter} 与 infra-storage 侧
 * {@code LingDataSourceProxy} / {@code NonCloseableLingConnectionProxy} 共享。
 * <p>
 * 状态分两类、传播方向相反：
 * <ul>
 *   <li><b>资源（Resource）</b>：穿透物理 Connection，按 dataSourceId 分栈，
 *       向下传递（调用方 → 被调方，随任务提交，经 {@link TransactionSnapshot#stacks}）；</li>
 *   <li><b>信号（Signal）</b>：rollbackOnly 标志，向上回传（被调方 → 调用方，
 *       经 {@link TransactionSnapshot#rollbackOnly} volatile 字段）。</li>
 * </ul>
 * <p>
 * 线程边界：栈本身是线程局部（单线程串行访问，无锁）；跨线程搬运由
 * {@link #captureSnapshot()} / {@link #applySnapshot} / {@link #restoreSnapshot} 完成，
 * {@link #restoreSnapshot} 采用<b>合并语义</b>（先把 worker 期间置位的 rollbackOnly 并入
 * 上行载体，再恢复 previous 状态），而非覆盖——覆盖式 restore 会丢弃 worker 置位的
 * 回滚信号，造成静默部分提交。
 */
public final class LingTransactionContext {

    /** 连接栈：dataSourceId → 连接栈（Deque 顶层为最近压入的连接） */
    private static final ThreadLocal<Map<String, Deque<Connection>>> CONNECTION_STACKS =
            new ThreadLocal<Map<String, Deque<Connection>>>() {
                @Override
                protected Map<String, Deque<Connection>> initialValue() {
                    return new HashMap<String, Deque<Connection>>();
                }
            };

    /** 压入顺序栈：记录 dataSourceId 的压入次序，供无参 {@link #popConnection()} 逐层配对弹栈 */
    private static final ThreadLocal<Deque<String>> PUSH_ORDER = new ThreadLocal<Deque<String>>() {
        @Override
        protected Deque<String> initialValue() {
            return new ArrayDeque<String>();
        }
    };

    /** 全局回滚信号（线程级）：任一下游声明回滚即置位，触发所有活跃根连接逐库回滚（best-effort） */
    private static final ThreadLocal<Boolean> ROLLBACK_ONLY = new ThreadLocal<Boolean>();

    private LingTransactionContext() {
    }

    /**
     * 按 dataSourceId 压入穿透连接。
     *
     * @param dataSourceId 数据源 ID（模式 1 恒为 "default"）
     * @param conn         穿透物理连接（灵核 TSM 提取的治理代理视图）
     */
    public static void pushConnection(String dataSourceId, Connection conn) {
        Map<String, Deque<Connection>> stacks = CONNECTION_STACKS.get();
        Deque<Connection> stack = stacks.get(dataSourceId);
        if (stack == null) {
            stack = new ArrayDeque<Connection>();
            stacks.put(dataSourceId, stack);
        }
        stack.push(conn);
        PUSH_ORDER.get().push(dataSourceId);
    }

    /**
     * 取指定 dataSourceId 的栈顶连接（穿透查找，身份门控由代理自身 dataSourceId 决定）。
     *
     * @param dataSourceId 数据源 ID
     * @return 栈顶连接；该源无连接时返回 null
     */
    public static Connection getCurrentConnection(String dataSourceId) {
        Deque<Connection> stack = CONNECTION_STACKS.get().get(dataSourceId);
        return stack == null || stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 取默认 dataSourceId（"default"）的栈顶连接。
     *
     * @return 栈顶连接；无连接时返回 null
     */
    public static Connection getCurrentConnection() {
        return getCurrentConnection("default");
    }

    /**
     * 弹出指定 dataSourceId 的栈顶连接。
     *
     * @param dataSourceId 数据源 ID
     */
    public static void popConnection(String dataSourceId) {
        Deque<Connection> stack = CONNECTION_STACKS.get().get(dataSourceId);
        if (stack != null && !stack.isEmpty()) {
            stack.pop();
            PUSH_ORDER.get().removeFirstOccurrence(dataSourceId);
            if (stack.isEmpty()) {
                CONNECTION_STACKS.get().remove(dataSourceId);
            }
        }
    }

    /**
     * 弹出最近压入的源的连接（Filter finally 逐层配对弹栈用）。
     * <p>
     * 与 {@link #pushConnection} 的压入顺序配对：每弹一次弹出最近一次压入的源，
     * 与 TransactionPropagationFilter 的「按活跃绑定源集合逐源压栈 → finally 逐源弹栈」
     * 严格对应。
     */
    public static void popConnection() {
        Deque<String> order = PUSH_ORDER.get();
        while (!order.isEmpty()) {
            String dataSourceId = order.pop();
            Deque<Connection> stack = CONNECTION_STACKS.get().get(dataSourceId);
            if (stack != null && !stack.isEmpty()) {
                stack.pop();
                if (stack.isEmpty()) {
                    CONNECTION_STACKS.get().remove(dataSourceId);
                }
                return;
            }
        }
    }

    /**
     * 栈是否非空（任意源）。
     *
     * @return 任意源有连接时返回 true
     */
    public static boolean hasAnyConnection() {
        return !CONNECTION_STACKS.get().isEmpty();
    }

    /**
     * 标记回滚信号（下游声明回滚意图；经快照合并语义上行回传）。
     */
    public static void setRollbackOnly() {
        ROLLBACK_ONLY.set(Boolean.TRUE);
    }

    /**
     * 当前线程是否已声明回滚。
     *
     * @return 已置位返回 true
     */
    public static boolean isRollbackOnly() {
        return Boolean.TRUE.equals(ROLLBACK_ONLY.get());
    }

    /**
     * 空栈即清 ThreadLocal（防线程池复用时的连接强引用残留与信号污染）。
     */
    public static void cleanIfEmpty() {
        if (CONNECTION_STACKS.get().isEmpty()) {
            CONNECTION_STACKS.remove();
            PUSH_ORDER.remove();
            ROLLBACK_ONLY.remove();
        }
    }

    /**
     * 无条件清空当前线程的全部穿透上下文（连接栈 / 压入顺序 / 回滚信号）。
     * <p>
     * 用于调用链强制结束（如根事务终止、框架异常路径收尾）与测试隔离——
     * 与 {@link #cleanIfEmpty()}（栈空才清）不同，本方法不检查栈状态直接清空。
     */
    public static void clear() {
        CONNECTION_STACKS.remove();
        PUSH_ORDER.remove();
        ROLLBACK_ONLY.remove();
    }

    /**
     * 废弃当前线程全部穿透连接（poisoned close）并清空上下文。
     * <p>
     * 用于超时/放弃执行路径：被放弃的 worker 可能仍占用同一物理连接，
     * 主线程跳过 rollback 直接 close 废弃该池连接（未提交写随 close 丢弃，
     * 连接池感知废弃后重建）——避免并发 rollback 的未定义行为。
     *
     * @return 实际废弃（close 成功）的连接数
     */
    public static int closeAllConnections() {
        Map<String, Deque<Connection>> stacks = CONNECTION_STACKS.get();
        int closed = 0;
        for (Deque<Connection> stack : stacks.values()) {
            while (!stack.isEmpty()) {
                Connection conn = stack.pop();
                if (conn == null) {
                    continue;
                }
                try {
                    conn.close();
                    closed++;
                } catch (Exception e) {
                    // 单个连接废弃失败不阻断整体流程，由连接池重建机制兜底
                }
            }
        }
        // 与 clear() 一致：三个 ThreadLocal 一并清空——只清连接栈而遗留 rollbackOnly 信号
        // 会让线程池复用线程带着「已声明回滚」的脏状态继续跑后续调用
        CONNECTION_STACKS.remove();
        PUSH_ORDER.remove();
        ROLLBACK_ONLY.remove();
        return closed;
    }

    /**
     * 捕获当前线程的事务上下文快照（跨线程搬运的下行载体）。
     *
     * @return 快照（含各源栈顶连接引用、压入顺序与当前 rollbackOnly）
     */
    public static TransactionSnapshot captureSnapshot() {
        Map<String, Connection> stacks = new HashMap<String, Connection>();
        for (Map.Entry<String, Deque<Connection>> entry : CONNECTION_STACKS.get().entrySet()) {
            Deque<Connection> stack = entry.getValue();
            if (stack != null && !stack.isEmpty()) {
                stacks.put(entry.getKey(), stack.peek());
            }
        }
        // 压入顺序一并快照：apply/restore 重建时必须还原 PUSH_ORDER，
        // 否则「无参 popConnection 与 push 严格配对」的弹栈语义在 worker 侧失效
        Deque<String> order = PUSH_ORDER.get();
        List<String> pushOrder = new ArrayList<String>(order.size());
        for (String dataSourceId : order) {
            pushOrder.add(dataSourceId);
        }
        return new TransactionSnapshot(stacks, pushOrder, isRollbackOnly());
    }

    /**
     * 在 worker 线程重放快照（把下行连接带入 worker 线程的上下文）。
     *
     * @param snapshot 主线程捕获的快照
     * @return worker 线程执行前的旧状态快照（供 {@link #restoreSnapshot} 恢复）
     */
    public static TransactionSnapshot applySnapshot(TransactionSnapshot snapshot) {
        TransactionSnapshot previous = captureSnapshot();
        Map<String, Deque<Connection>> stacks = new HashMap<String, Deque<Connection>>();
        if (snapshot != null && snapshot.stacks != null) {
            for (Map.Entry<String, Connection> entry : snapshot.stacks.entrySet()) {
                Deque<Connection> stack = new ArrayDeque<Connection>();
                stack.push(entry.getValue());
                stacks.put(entry.getKey(), stack);
            }
        }
        CONNECTION_STACKS.set(stacks);
        // 还原压入顺序（与栈配对：每个源一次），保证 worker 侧无参 pop 与 push 语义一致
        PUSH_ORDER.set(new ArrayDeque<String>(snapshot == null || snapshot.pushOrder == null
                ? Collections.<String>emptyList() : snapshot.pushOrder));
        if (snapshot != null && snapshot.rollbackOnly) {
            setRollbackOnly();
        }
        return previous;
    }

    /**
     * 合并语义恢复：先把 worker 线程当前置位的 rollbackOnly 并入上行载体 carrier，
     * 再恢复 worker 线程为 previous（执行前）状态。
     *
     * @param previous worker 线程执行前的状态快照（{@link #applySnapshot} 返回值）
     * @param carrier  上行载体（worker 期间置位的信号写入此对象，供主线程 future.get() 后读取）
     */
    public static void restoreSnapshot(TransactionSnapshot previous, TransactionSnapshot carrier) {
        // 1. 合并上行：worker 期间置位的信号并入 carrier（覆盖式恢复会把它丢弃 → 静默部分提交）
        if (carrier != null && isRollbackOnly()) {
            carrier.rollbackOnly = true;
        }
        // 2. 恢复 worker 线程为 previous 状态（干净状态）
        Map<String, Deque<Connection>> stacks = new HashMap<String, Deque<Connection>>();
        if (previous != null && previous.stacks != null) {
            for (Map.Entry<String, Connection> entry : previous.stacks.entrySet()) {
                Deque<Connection> stack = new ArrayDeque<Connection>();
                stack.push(entry.getValue());
                stacks.put(entry.getKey(), stack);
            }
        }
        CONNECTION_STACKS.set(stacks);
        // 还原 previous 的压入顺序（与栈配对），不因快照恢复而丢失弹栈配对信息
        PUSH_ORDER.set(new ArrayDeque<String>(previous == null || previous.pushOrder == null
                ? Collections.<String>emptyList() : previous.pushOrder));
        if (previous != null && previous.rollbackOnly) {
            setRollbackOnly();
        } else {
            ROLLBACK_ONLY.remove();
        }
        cleanIfEmpty();
    }

    /**
     * 跨线程搬运的双向载体。
     * <p>
     * 下行携带连接栈（{@link #stacks}）与压入顺序（{@link #pushOrder}），
     * 上行携带 rollbackOnly 信号（{@link #rollbackOnly}，volatile 保证 worker 写 /
     * 主线程读的跨线程可见性）。
     */
    public static final class TransactionSnapshot {

        /** 下行：连接栈引用（dataSourceId → 栈顶连接），捕获时浅拷贝 */
        private final Map<String, Connection> stacks;

        /** 下行：压入顺序（最近压入在前），用于 worker 侧重建 PUSH_ORDER 保持弹栈配对语义 */
        private final List<String> pushOrder;

        /** 上行：rollbackOnly 信号（volatile，worker 写 / 主线程读——跨线程可见性保证） */
        private volatile boolean rollbackOnly;

        private TransactionSnapshot(Map<String, Connection> stacks, List<String> pushOrder, boolean rollbackOnly) {
            this.stacks = stacks;
            this.pushOrder = pushOrder;
            this.rollbackOnly = rollbackOnly;
        }

        /**
         * 上行载体是否已带回滚信号。
         *
         * @return worker 期间置位返回 true
         */
        public boolean isRollbackOnly() {
            return rollbackOnly;
        }
    }
}
