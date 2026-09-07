package com.lingframe.starter.transaction;

import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionRollbackException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;

/**
 * 受管模式灵元容器专用事务管理器（双路径）。
 * <p>
 * 仅在分支 B（受管共享，穿透开启）灵元容器注册。实现 Spring
 * {@link PlatformTransactionManager}，但<b>不激活 TSM 资源绑定</b>——灵元侧 TSM 全程空置，
 * 从根上避免跨 ClassLoader 双层 {@code TransactionSynchronizationManager} 竞态冲突。
 * <p>
 * 判根真源：{@code getTransaction()} 调用时刻 {@link LingTransactionContext} 连接栈
 * （按 dataSourceId）<b>空 → 根；非空 → 加入</b>。
 * <ul>
 *   <li><b>根路径</b>（灵元为事务根，如纯灵元发起业务事务）：借连接（普通治理代理）→
 *       隔离级别/readOnly（借出时一次性设置）→ {@code setAutoCommit(false)} → push；
 *       commit/rollback 物理执行 + pop + close 归还池（autoCommit 复位交由池 reset）；</li>
 *   <li><b>加入路径</b>（栈非空，灵核侧入口 Bean 已开根事务）：不 bind TSM、不碰连接；
 *       非根 commit 前检测 rollbackOnly，置位则抛 {@link LingTransactionRollbackException}
 *       （对齐 Spring {@code UnexpectedRollbackException} 语义）。</li>
 * </ul>
 * <p>
 * 传播语义：{@code REQUIRES_NEW} 等非 REQUIRED 传播物理不可达（共享单一物理连接），
 * 显式降级为加入并告警；timeout 不由本管理器实现，由流水线 resilience 治理兜底。
 */
@Slf4j
public class LingManagedTransactionManager implements PlatformTransactionManager {

    private final DataSource managedDataSource;
    private final String dataSourceId;

    public LingManagedTransactionManager(DataSource managedDataSource, String dataSourceId) {
        // 入口固化契约边界：null 依赖在装配期显式失败，而非延迟到运行时难定位
        this.managedDataSource = Objects.requireNonNull(managedDataSource, "managedDataSource must not be null");
        this.dataSourceId = Objects.requireNonNull(dataSourceId, "dataSourceId must not be null");
    }

    /**
     * 受管数据源身份（与本灵元 dataSource Bean 的 id 一致）。
     *
     * @return dataSourceId
     */
    public String getDataSourceId() {
        return dataSourceId;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        int propagation = definition.getPropagationBehavior();
        // 显式声明「在事务外运行」（NOT_SUPPORTED）或「存在活跃事务即抛错」（NEVER）的传播：
        // 本管理器只支持共享单一物理连接的 REQUIRED 语义，无法提供事务外执行——静默降级为加入
        // 会反转开发者意图（本应在事务外执行的写被纳入根事务提交/回滚，数据完整性受损），故拒绝
        if (propagation == TransactionDefinition.PROPAGATION_NEVER
                || propagation == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
            throw new IllegalTransactionStateException(
                    "Propagation " + propagation + " (NEVER/NOT_SUPPORTED) is not supported by "
                            + "LingManagedTransactionManager: managed ling shares a single physical connection "
                            + "and cannot run outside the root transaction");
        }

        Connection current = LingTransactionContext.getCurrentConnection(dataSourceId);
        // MANDATORY 声明「必须加入已有事务」：栈空（无根事务可加入）时拒绝而非自开根事务
        if (propagation == TransactionDefinition.PROPAGATION_MANDATORY && current == null) {
            throw new IllegalTransactionStateException(
                    "Propagation MANDATORY requires an existing root transaction, but none is active");
        }
        // REQUIRES_NEW / NESTED 等非 REQUIRED 传播：物理不可达（共享单一物理连接），显式降级 + 告警
        if (propagation != TransactionDefinition.PROPAGATION_REQUIRED) {
            log.warn("LingManagedTransactionManager only supports REQUIRED; propagation {} demoted to REQUIRED",
                    propagation);
        }
        // SimpleTransactionStatus 的 newTransaction 为 final，须构造时指定：栈空 -> 根（true），
        // 栈非空 -> 加入（false）；isNewTransaction() 是根/加入判定与 commit/rollback 分支的依据
        SimpleTransactionStatus status = new SimpleTransactionStatus(current == null);
        if (current == null) {
            // ===== 根路径（灵元为事务根：无灵核事务、也无更早的灵元事务）=====
            Connection conn = null;
            boolean handoverSuccess = false;
            try {
                conn = managedDataSource.getConnection();   // 普通借出（治理代理，走语句治理）
                // 隔离级别 / readOnly 仅根路径生效（借出时设置；非根场景由根事务决定，下游篡改已被拦截）
                if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
                    conn.setTransactionIsolation(definition.getIsolationLevel());
                }
                if (definition.isReadOnly()) {
                    conn.setReadOnly(true);
                }
                conn.setAutoCommit(false);
                LingTransactionContext.pushConnection(dataSourceId, conn);
                // 接管标志：置位即表示所有权已移交穿透上下文，归还由 commit/rollback 负责
                handoverSuccess = true;
            } catch (Exception e) {
                throw new CannotCreateTransactionException(
                        "Failed to create root transaction on managed datasource '" + dataSourceId + "'", e);
            } finally {
                // 仅在连接尚未被穿透上下文接管时归还（防池连接泄漏）；避免「已入栈但标志未置位」
                // 的窄窗口内误关已移交准备提交的连接，破坏事务上下文一致性
                if (!handoverSuccess) {
                    closeQuietly(conn);
                }
            }
        }
        // 非根：status 保持 non-new（加入），不 bind TSM、不碰连接
        return status;
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        if (!status.isNewTransaction()) {
            // ===== 加入路径 commit：物理提交权归根事务发起方 =====
            // 非根 commit 前必须检测 rollbackOnly——下游已声明回滚（吞异常场景：内层 rollback
            // 仅置标志、外层 catch 后继续 commit）时，静默 return 会让根 commit 提交已声明回滚
            // 的写——对齐 Spring UnexpectedRollbackException 语义
            if (LingTransactionContext.isRollbackOnly()) {
                throw new LingTransactionRollbackException(
                        "Transaction marked as rollbackOnly but joiner attempted commit");
            }
            return;   // 正常加入：无物理动作
        }
        // ===== 根路径 commit：物理提交 + 归还 =====
        Connection conn = LingTransactionContext.getCurrentConnection(dataSourceId);
        if (conn == null) {
            // 防御：根事务连接缺失（上下文被清空 / poisoned 废弃 / 外部误调）时，
            // 明确报错而不是 NPE——否则事务状态不可判、池连接可能泄漏
            throw new TransactionSystemException(
                    "Root transaction connection missing on managed datasource '" + dataSourceId
                            + "' at commit: context was cleared or poisoned before commit");
        }
        try {
            conn.commit();
        } catch (Exception e) {
            throw new TransactionSystemException(
                    "Failed to commit root transaction on managed datasource '" + dataSourceId + "'", e);
        } finally {
            LingTransactionContext.popConnection(dataSourceId);
            LingTransactionContext.cleanIfEmpty();
            closeQuietly(conn);
        }
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        if (!status.isNewTransaction()) {
            // ===== 加入路径 rollback：置 rollbackOnly 信号（经快照合并语义上行）=====
            LingTransactionContext.setRollbackOnly();
            return;
        }
        // ===== 根路径 rollback：物理回滚 + 归还 =====
        Connection conn = LingTransactionContext.getCurrentConnection(dataSourceId);
        if (conn == null) {
            // 防御：根事务连接缺失时明确报错而不是 NPE（语义同上 commit 路径）
            throw new TransactionSystemException(
                    "Root transaction connection missing on managed datasource '" + dataSourceId
                            + "' at rollback: context was cleared or poisoned before rollback");
        }
        try {
            conn.rollback();
        } catch (Exception e) {
            throw new TransactionSystemException(
                    "Failed to rollback root transaction on managed datasource '" + dataSourceId + "'", e);
        } finally {
            LingTransactionContext.popConnection(dataSourceId);
            LingTransactionContext.cleanIfEmpty();
            closeQuietly(conn);
        }
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            // 归还池（池自身 reset autoCommit/isolation，不依赖手动复位）
            conn.close();
        } catch (Exception e) {
            log.warn("Failed to close connection on managed datasource '{}': {}", dataSourceId, e.getMessage());
        }
    }
}
