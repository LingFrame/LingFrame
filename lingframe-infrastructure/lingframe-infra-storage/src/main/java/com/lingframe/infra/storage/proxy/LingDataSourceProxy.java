package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 数据源代理
 * 职责：劫持 getConnection 方法，为返回的 Connection 实例自动套上治理代理，
 * 从而实现对 JDBC 执行层面的权限管控和审计映射。
 * <p>
 * 受管模式（模式 1 灵核供给 / 模式 3 存储灵元供给）下，代理携带 {@code dataSourceId}
 * 身份：{@link #getConnection()} 优先按自身 id 精确查穿透上下文连接栈（身份门控），
 * 命中则复用穿透物理连接（返回不可物理关闭的 {@link NonCloseableLingConnectionProxy}），
 * 否则从自身连接池借出新连接。
 * <p>
 * 模式 2 私有池代理的 dataSourceId 为 null——永不查栈，混合链路下绝不误用受管连接（防串库）。
 */
public class LingDataSourceProxy implements DataSource {

    private final DataSource target;
    private final PermissionService permissionService;

    /**
     * 受管数据源身份：模式 1 为 "default"，模式 3 为各自 dataSourceId，模式 2 私有池为 null。
     * <p>
     * volatile + {@link #promoteToManaged}：灵核装配路径（{@code DataSourceWrapperProcessor} 包装）
     * 产生的代理先以 null 身份存在，待灵核侧受管总线装配时【同实例】提升身份——
     * TSM 资源键以实例为键，提升不换实例，灵核 {@code DataSourceTransactionManager} 与总线查找
     * 才能命中同一对象；换实例会导致 TSM 键失配、穿透静默失效。身份提升发生在启动期
     * （总线 Bean 装配），先于任何事务流量，volatile 保证可见性即可。
     */
    private volatile String dataSourceId;

    /**
     * 既有构造器（保留，行为与现状完全一致）：模式 2 私有池装配路径使用，dataSourceId 为 null。
     */
    public LingDataSourceProxy(DataSource target, PermissionService permissionService) {
        this(target, permissionService, null);
    }

    /**
     * 受管构造器：携带 dataSourceId 身份，启用穿透连接复用（身份门控）。
     *
     * @param target            底层连接池
     * @param permissionService 权限服务
     * @param dataSourceId      受管数据源身份（模式 1 为 "default"，模式 3 为各自 ID；null 表示私有池不查栈）
     */
    public LingDataSourceProxy(DataSource target, PermissionService permissionService, String dataSourceId) {
        this.target = target;
        this.permissionService = permissionService;
        this.dataSourceId = dataSourceId;
    }

    /**
     * 提升为受管数据源（同实例设置身份，幂等）。
     * <p>
     * 供灵核装配（模式 1 "default"）与存储灵元装配（模式 3 各自 ID）在注册到
     * {@code ManagedDataSourceRegistry} 时调用——BPP 已包装的代理先以 null 身份存在，
     * 注册即提升。已具备相同身份时 no-op；已具备不同身份（复用同一代理供给多源）属装配错误，
     * 抛 {@link IllegalStateException} 防御——同一物理池以两个身份供给会造成连接串用。
     *
     * @param dataSourceId 受管数据源身份
     * @throws IllegalStateException 该代理已具备不同身份时抛出
     */
    public void promoteToManaged(String dataSourceId) {
        String current = this.dataSourceId;
        if (current != null) {
            if (current.equals(dataSourceId)) {
                return;
            }
            throw new IllegalStateException("LingDataSourceProxy already managed as '" + current
                    + "', cannot promote to '" + dataSourceId + "'");
        }
        this.dataSourceId = dataSourceId;
    }

    @Override
    public Connection getConnection() throws SQLException {
        // 1.【身份门控】受管代理携带 dataSourceId，用自身 id 精确查穿透上下文连接栈；
        //    模式 2 私有池代理 dataSourceId 为 null —— 永不查栈，混合链路下绝不误用受管连接（防串库）
        if (dataSourceId != null) {
            Connection txConnection = LingTransactionContext.getCurrentConnection(dataSourceId);
            if (txConnection != null && !txConnection.isClosed()) {
                // 返回不可物理关闭的包装代理，确保灵元内 DAO 层 close() 仅归还逻辑连接
                return new NonCloseableLingConnectionProxy(txConnection, permissionService);
            }
        }

        // 2. 无同身份穿透连接时，按原逻辑从自身 target 连接池借出新连接
        Connection connection = target.getConnection();
        return new LingConnectionProxy(connection, permissionService);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // 禁止灵元用任意凭据连接，强制使用灵核配置的连接池凭据
        throw new SQLException("getConnection(username, password) is forbidden on governed DataSource, "
                + "use getConnection() with configured credentials");
    }

    // --- 下面是必须实现的委托方法 ---
    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return (T) this;
        }
        // 拒绝暴露原生数据源实现（如 HikariDataSource），防止绕过治理代理
        throw new SQLException("Cannot unwrap to " + iface.getName()
                + ": LingDataSourceProxy only exposes the DataSource interface");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return target.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        target.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        target.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return target.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return target.getParentLogger();
    }
}
