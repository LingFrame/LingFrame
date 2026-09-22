package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 不可物理关闭的连接代理（穿透连接的非关闭变体）。
 * <p>
 * 当下游灵元复用穿透物理连接时，本代理将 {@code close() / commit() / setAutoCommit()} 及
 * 根连接属性修改（隔离级别/只读/保持性）降级为 no-op，物理提交/回滚/生命周期权归根事务
 * 发起方；{@link #rollback()} 仅标记 {@link LingTransactionContext#setRollbackOnly()}，
 * 回滚信号经快照合并语义上行回传。
 * <p>
 * <b>单层 Statement 委托</b>：穿透连接本身已是 {@link LingConnectionProxy}（治理代理）。
 * 本代理的 Statement 工厂直接委托内层 {@code target}（其已包 {@link LingStatementProxy} /
 * {@link LingPreparedStatementProxy} 完成权限检查与审计），仅用薄代理修正
 * {@code getConnection()} 视图指向本代理——避免「对内层代理再包一层 Statement」导致
 * 每次 SQL 执行两遍权限检查与两遍审计（审计计数虚高、性能翻倍）。
 * <p>
 * <b>审计不降级</b>：no-op 降级的只是<b>物理行为</b>——{@code checkTransactionPermission}
 * 事务权限门与 {@code downstream-*-suppressed} 审计事件全部保留，下游对共享连接的每一次
 * 事务性尝试都可观测、可审计、可拒绝。
 */
public class NonCloseableLingConnectionProxy extends LingConnectionProxy {

    public NonCloseableLingConnectionProxy(Connection target, PermissionService permissionService) {
        super(target, permissionService);
    }

    @Override
    public void close() throws SQLException {
        // 空实现：禁止下游灵元提前归还物理连接至池中，生命周期交由上游事务发起方管辖
    }

    @Override
    public void commit() throws SQLException {
        // 物理行为降级为 no-op，但权限检查与审计保留——no-op 不豁免治理门
        checkTransactionPermission("commit");
        auditSuppressed("commit");
        // 空执行：禁止下游灵元私自提交，统一由根事务发起方负责最终 commit
    }

    @Override
    public void rollback() throws SQLException {
        // 权限检查保留（拒绝时照常抛出，下游不能借 rollback 通道绕过审计）
        checkTransactionPermission("rollback");
        // 与 commit/setAutoCommit 一致：物理回滚被抑制也须记 suppressed 审计事件，
        // 保证 lingframe.tx.suppressed.attempts 的 rollback tag 可产生（治理可观测、可审计）
        auditSuppressed("rollback");
        // 下游若触发回滚，将共享事务上下文标记为 rollbackOnly，经快照合并语义上行回传
        LingTransactionContext.setRollbackOnly();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        // 权限检查保留（no-op 不豁免治理门）
        checkTransactionPermission("setAutoCommit");
        auditSuppressed("setAutoCommit");
        // 空执行：禁止下游灵元私自篡改共享连接的自动提交状态
    }

    // 根连接属性防篡改：中途改隔离级别/只读/保持性会影响根事务后续语句语义
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        auditSuppressed("isolation");
        // 空执行：隔离级别由根事务借出时设置，下游不可中途篡改
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        auditSuppressed("readonly");
        // 空执行：同上
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        auditSuppressed("holdability");
        // 空执行：同上
    }

    // ===== 单层 Statement 委托（见类注释）：内层 target 已完成治理，薄代理只修正视图 =====

    @Override
    public Statement createStatement() throws SQLException {
        return delegateStatement(target.createStatement());
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return delegateStatement(target.prepareStatement(sql));
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        return delegateStatement(target.prepareCall(sql));
    }

    /**
     * 薄 Statement 委托：转发内层（已治理）Statement 的全部调用，仅拦截
     * {@code getConnection()} 返回本代理——确保灵元框架经 Statement 拿到的连接视图
     * 仍是「不可物理关闭」的，close/commit 等防越权语义不被绕过。
     * <p>
     * 用 JDK 动态代理（接口组合按实际类型解析），不引入新代理类；反射调用经
     * {@link InvocationTargetException} 解包还原原始 SQLException。
     *
     * @param statement 内层已治理的 Statement（非空）
     * @param <T>       Statement 子类型
     * @return 视图修正后的代理
     */
    @SuppressWarnings("unchecked")
    private <T extends Statement> T delegateStatement(T statement) {
        if (statement == null) {
            return null;
        }
        Class<?>[] interfaces = resolveStatementInterfaces(statement);
        return (T) Proxy.newProxyInstance(LingConnectionProxy.class.getClassLoader(), interfaces,
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName()) && method.getParameterCount() == 0) {
                        return NonCloseableLingConnectionProxy.this;
                    }
                    try {
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException e) {
                        // 还原驱动抛出的原始异常（SQLException 等），不包一层 InvocationTargetException
                        throw e.getCause() != null ? e.getCause() : e;
                    }
                });
    }

    private static Class<?>[] resolveStatementInterfaces(Statement statement) {
        if (statement instanceof CallableStatement) {
            return new Class<?>[]{CallableStatement.class};
        }
        if (statement instanceof PreparedStatement) {
            return new Class<?>[]{PreparedStatement.class};
        }
        return new Class<?>[]{Statement.class};
    }
}
