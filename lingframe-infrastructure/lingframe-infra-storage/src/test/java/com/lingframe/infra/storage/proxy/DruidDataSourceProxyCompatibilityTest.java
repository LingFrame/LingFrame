package com.lingframe.infra.storage.proxy;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Druid 连接池包装兼容测试。
 * <p>
 * 验证 {@link LingDataSourceProxy} 包装真实 Druid 数据源时的兼容性：
 * 借出/归还连接、unwrap 语义、StatFilter 共存、穿透命中边界——防止治理代理
 * 与 Druid 自身代理体系（DruidPooledConnection / StatFilter）发生类型强转或
 * 反射拦截冲突导致连接借还失效或治理旁路。
 */
@DisplayName("Druid 连接池包装兼容")
class DruidDataSourceProxyCompatibilityTest {

    @AfterEach
    void tearDown() {
        // 每个用例后清空穿透上下文，防止 ThreadLocal 跨用例残留污染
        LingTransactionContext.clear();
    }

    /**
     * 构造指向 H2 内存库的真实 Druid 数据源（带最小池参数，便于验证借还语义）。
     */
    private static DruidDataSource newH2DruidDataSource() {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl("jdbc:h2:mem:lingframe-druid-compat;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setDriverClassName("org.h2.Driver");
        ds.setInitialSize(1);
        ds.setMinIdle(1);
        ds.setMaxActive(4);
        return ds;
    }

    /** 用治理代理包装 Druid 数据源（携带受管身份，启用穿透复用）。 */
    private static LingDataSourceProxy wrapManaged(DruidDataSource target) {
        PermissionService permissionService = mock(PermissionService.class);
        return new LingDataSourceProxy(target, permissionService, "default");
    }

    @Nested
    @DisplayName("正常路径：借出与归还")
    class BorrowAndReturn {

        @Test
        @DisplayName("经受管代理借出的连接可执行 SQL（建表/插入/查询），关闭后归还 Druid 池")
        void borrowExecuteAndReturn() throws SQLException {
            DruidDataSource druid = newH2DruidDataSource();
            LingDataSourceProxy proxy = wrapManaged(druid);

            try (Connection conn = proxy.getConnection()) {
                assertInstanceOf(LingConnectionProxy.class, conn);
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS druid_compat (id INT PRIMARY KEY, note VARCHAR(32))");
                    st.execute("INSERT INTO druid_compat VALUES (1, 'borrow-return')");
                    try (ResultSet rs = st.executeQuery("SELECT note FROM druid_compat WHERE id = 1")) {
                        assertTrue(rs.next());
                        assertNotNull(rs.getString(1));
                    }
                }
            }

            // 连接已归还：Druid 池无活跃借出（治理代理 close 委托底层 close 归还）
            assertTrue(druid.getActiveCount() == 0,
                    "治理代理 close 后连接应归还 Druid 池");
            druid.close();
        }

        @Test
        @DisplayName("穿透栈命中时经受管代理借出的是不可物理关闭的复用代理，不触碰 Druid 池")
        void penetrationHitReusesStackConnection() throws SQLException {
            DruidDataSource druid = newH2DruidDataSource();
            LingDataSourceProxy proxy = wrapManaged(druid);

            // 模拟根事务连接已压栈（穿透激活）
            try (Connection root = druid.getConnection()) {
                LingTransactionContext.pushConnection("default", root);
                try (Connection conn = proxy.getConnection()) {
                    assertInstanceOf(NonCloseableLingConnectionProxy.class, conn);
                    // 复用穿透连接，Druid 池不再新增借出
                    assertTrue(druid.getActiveCount() <= 1, "穿透命中时不应向 Druid 池借新连接");
                }
                LingTransactionContext.popConnection();
            }
            druid.close();
        }
    }

    @Nested
    @DisplayName("unwrap 语义")
    class UnwrapSemantics {

        @Test
        @DisplayName("unwrap 到治理代理自身或 DataSource 接口成功，unwrap 到原生 Druid 类型被拒绝（防治理旁路）")
        void unwrapGuardsNativeType() throws SQLException {
            DruidDataSource druid = newH2DruidDataSource();
            LingDataSourceProxy proxy = wrapManaged(druid);

            // 治理代理自身与 DataSource 接口可 unwrap（标准 JDBC 语义）
            LingDataSourceProxy self = proxy.unwrap(LingDataSourceProxy.class);
            assertNotNull(self);
            assertNotNull(proxy.unwrap(DataSource.class));

            // 拒绝暴露原生 Druid 实现，防止绕过治理代理直接触达池
            assertThrows(SQLException.class, () -> proxy.unwrap(DruidDataSource.class));
            druid.close();
        }
    }

    @Nested
    @DisplayName("StatFilter 共存")
    class StatFilterCoexistence {

        @Test
        @DisplayName("Druid 开启 StatFilter 后经受管代理执行 SQL 正常，Druid 统计可查（双层代理不冲突）")
        void statFilterCoexistsWithGovernanceProxy() throws Exception {
            DruidDataSource druid = newH2DruidDataSource();
            druid.setFilters("stat");
            LingDataSourceProxy proxy = wrapManaged(druid);

            try (Connection conn = proxy.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS druid_stat (id INT PRIMARY KEY)");
                    st.execute("INSERT INTO druid_stat VALUES (1)");
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM druid_stat")) {
                        assertTrue(rs.next());
                        assertTrue(rs.getInt(1) >= 1);
                    }
                }
            }

            // StatFilter 统计表已记录 SQL 执行次数（证明 Druid 过滤器链未被治理代理打断）
            assertTrue(druid.getDataSourceStat().getSqlStatMap().size() >= 1,
                    "StatFilter 应能统计经受管代理执行的 SQL");
            druid.close();
        }
    }

    @Nested
    @DisplayName("WallFilter 共存")
    class WallFilterCoexistence {

        /**
         * 构造开启 WallFilter（SQL 防火墙）的 Druid 数据源：
         * 禁用 DELETE，验证治理代理与防火墙双层共存时，正常 SQL 放行、恶意 SQL 被拦截。
         */
        private DruidDataSource newH2DruidWithWallBlockingDelete() {
            DruidDataSource ds = newH2DruidDataSource();
            WallConfig wallConfig = new WallConfig();
            wallConfig.setDeleteAllow(false);
            wallConfig.setSelectAllow(true);
            wallConfig.setInsertAllow(true);
            wallConfig.setUpdateAllow(true);
            wallConfig.setMultiStatementAllow(false);
            WallFilter wallFilter = new WallFilter();
            wallFilter.setConfig(wallConfig);
            wallFilter.setDbType("h2");
            wallFilter.setThrowException(true);
            ds.setProxyFilters(Collections.singletonList(wallFilter));
            return ds;
        }

        @Test
        @DisplayName("WallFilter 开启时经受管代理执行正常 SELECT/INSERT 放行（双层代理不冲突）")
        void wallAllowsNormalSql() throws SQLException {
            DruidDataSource druid = newH2DruidWithWallBlockingDelete();
            LingDataSourceProxy proxy = wrapManaged(druid);

            try (Connection conn = proxy.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS druid_wall (id INT PRIMARY KEY, note VARCHAR(32))");
                    st.execute("INSERT INTO druid_wall VALUES (1, 'wall-ok')");
                    try (ResultSet rs = st.executeQuery("SELECT note FROM druid_wall WHERE id = 1")) {
                        assertTrue(rs.next());
                        assertNotNull(rs.getString(1));
                    }
                }
            }
            druid.close();
        }

        @Test
        @DisplayName("WallFilter 禁用 DELETE 后经受管代理执行 DELETE 被防火墙拦截（异常经受管代理传播，不被吞掉）")
        void wallBlocksForbiddenDelete() throws SQLException {
            DruidDataSource druid = newH2DruidWithWallBlockingDelete();
            LingDataSourceProxy proxy = wrapManaged(druid);

            // 独立表名：与 wallAllowsNormalSql 的 druid_wall 隔离，避免 H2 内存库跨用例残留主键冲突
            try (Connection conn = proxy.getConnection()) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS druid_wall_block (id INT PRIMARY KEY, note VARCHAR(32))");
                    st.execute("INSERT INTO druid_wall_block VALUES (1, 'wall-block')");
                }
                // 防火墙拦截：DELETE 被 WallFilter 拒绝，SQLException 经受管代理正常传播
                try (Statement st = conn.createStatement()) {
                    assertThrows(SQLException.class, () -> st.execute("DELETE FROM druid_wall_block WHERE id = 1"));
                }
                // 治理代理仍可继续使用：拦截只是 Druid 层行为，不影响治理代理连接本身
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT COUNT(*) FROM druid_wall_block");
                }
            }
            druid.close();
        }
    }

    @Nested
    @DisplayName("拒绝路径")
    class RejectPaths {

        @Test
        @DisplayName("带用户名密码的 getConnection 被拒绝（强制使用池配置凭据），不触碰 Druid")
        void rejectGetConnectionWithCredentials() throws SQLException {
            DruidDataSource druid = newH2DruidDataSource();
            LingDataSourceProxy proxy = wrapManaged(druid);

            assertThrows(SQLException.class, () -> proxy.getConnection("sa", ""));
            assertTrue(druid.getActiveCount() == 0, "被拒绝的调用不应向 Druid 池借出连接");
            druid.close();
        }
    }
}
