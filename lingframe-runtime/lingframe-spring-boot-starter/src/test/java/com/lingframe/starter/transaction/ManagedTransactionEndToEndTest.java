package com.lingframe.starter.transaction;

import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionRollbackException;
import com.lingframe.api.storage.ManagedDataSourceRegistry;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.TransactionPropagationFilter;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import com.lingframe.infra.storage.proxy.NonCloseableLingConnectionProxy;
import com.lingframe.starter.resource.LingTestSpringConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 Spring 容器端到端：灵核根事务 + 灵元 Mapper 回滚链路。
 * <p>
 * 走生产真实装配（非 mock、非复制逻辑）：
 * <ol>
 *   <li>{@code @EnableAutoConfiguration} 装配嵌入式 H2 与 {@code DataSourceTransactionManager}（JDBC 根，穿透前提）；</li>
 *   <li>{@code DataSourceWrapperProcessor}（BPP）把灵核 H2 数据源包装为 {@code LingDataSourceProxy}，
 *       {@code managedDataSourceRegistry} 懒解析并【同实例】提升为 "default" 注册总线（TSM 资源键一致性契约）；</li>
 *   <li>{@code filterRegistry} 装配真实 {@code TransactionPropagationFilter} + {@code SpringTransactionBindingHook}；</li>
 *   <li>灵元 Mapper 经受管代理（总线 lookup）执行 SQL：穿透激活时复用根连接（返回
 *       {@link NonCloseableLingConnectionProxy}，不向池借新连接），回滚信号经快照语义上行。</li>
 * </ol>
 * 验证点：穿透复用 / rollbackOnly 信号 → 根事务真实回滚（数据不落库）/ 正常提交（数据落库）/ 异常回滚。
 * <p>
 * 权限说明：测试配置关闭灵核权限检查（灵核身份调用豁免权限表），灵元 SQL 不经授权拦截，
 * 聚焦事务穿透链路本身；权限治理语义由 {@code PermissionGovernanceFilter} 单测独立覆盖。
 */
@SpringBootTest(classes = {LingTestSpringConfiguration.class, ManagedTransactionEndToEndTest.DataSourceConfig.class}, properties = {
        "lingframe.enabled=true",
        "lingframe.dev-mode=true",
        "lingframe.core.check-permissions=false",
        "lingframe.tx.propagation.enabled=true",
        // 排除 DataSourceAutoConfiguration：其 DataSourceInitializerPostProcessor（BeanFactoryPostProcessor）
        // 会在 BeanPostProcessor 注册阶段提前实例化 dataSource bean，导致 DataSourceWrapperProcessor
        // 来不及包装（TSM 键失配、穿透静默失效）。本测试显式定义 DataSource 与事务管理器。
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("真实容器端到端：根事务 + 灵元 Mapper 回滚链路")
class ManagedTransactionEndToEndTest {

    private static final String E2E_TABLE = "e2e_tx_propagation";

    @Autowired
    private ManagedDataSourceRegistry managedDataSourceRegistry;

    @Autowired
    private FilterRegistry filterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource coreDataSource;

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    /**
     * 显式定义灵核 DataSource 与 JDBC 根事务管理器。
     * <p>
     * 装配背景：本测试容器中 dataSource 会在 BeanPostProcessor 注册阶段被过早实例化
     * （{@code filterRegistry} 装配链在 {@code registerBeanPostProcessors} 期间被强制创建，
     * 早于 {@code DataSourceWrapperProcessor} 生效），裸数据源无法被自动包装。故此处
     * 直接以受管代理形式声明 DataSource——{@code managedDataSourceRegistry} 装配 lambda
     * 检测到 {@code instanceof LingDataSourceProxy} 后走【同实例】promoteToManaged 分支
     * （即生产 BPP 已包装时的同一路径），灵核 {@code DataSourceTransactionManager} 亦持有
     * 该代理实例 → TSM 资源键一致 → 穿透真实激活。自动包装本身由
     * {@code ManagedAssemblyChainContractTest} 独立覆盖，本测试聚焦其下游完整回滚链路。
     */
    @TestConfiguration
    static class DataSourceConfig {

        @Bean
        public DataSource dataSource(PermissionService permissionService) {
            DriverManagerDataSource raw = new DriverManagerDataSource();
            raw.setDriverClassName("org.h2.Driver");
            raw.setUrl("jdbc:h2:mem:lingframe-tx-e2e;DB_CLOSE_DELAY=-1");
            raw.setUsername("sa");
            raw.setPassword("");
            // 受管代理形态声明（模拟 DataSourceWrapperProcessor 已包装的结果）：
            // dataSourceId 为 null，待总线装配时同实例提升为 "default"
            return new LingDataSourceProxy(raw, permissionService);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            // JDBC 根事务管理器：TSM 资源键 = 受管代理实例（与总线查找同一实例）
            return new DataSourceTransactionManager(dataSource);
        }
    }

    private TransactionPropagationFilterHolder realPropagationFilter() {
        return new TransactionPropagationFilterHolder(filterRegistry);
    }

    private JdbcTemplate jdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private void ensureTable(DataSource dataSource) {
        jdbc(dataSource).execute("CREATE TABLE IF NOT EXISTS " + E2E_TABLE
                + " (id INT PRIMARY KEY, note VARCHAR(64))");
    }

    /** 从真实装配的 FilterRegistry 中取回事务穿透过滤器实例（非 new，保证 hook 为真实 Spring 实现）。 */
    private static final class TransactionPropagationFilterHolder {
        private final TransactionPropagationFilter filter;

        TransactionPropagationFilterHolder(FilterRegistry registry) {
            this.filter = registry.getOrderedFilters().stream()
                .filter(f -> f instanceof TransactionPropagationFilter)
                .map(f -> (TransactionPropagationFilter) f)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TransactionPropagationFilter not assembled in FilterRegistry"));
        }

        LingInvocationFilter get() {
            return filter;
        }
    }

    private InvocationContext normalContext() {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.attach();
        ctx.execution().setMode(InvocationExecutionMode.NORMAL);
        return ctx;
    }

    @Nested
    @DisplayName("穿透复用：灵元经受管代理复用根连接")
    class PenetrationReuse {

        @Test
        @DisplayName("根事务内灵元经受管代理拿到不可物理关闭的穿透连接（不向池借新连接）")
        void lingReusesRootConnectionViaManagedProxy() throws Throwable {
            ensureTable(coreDataSource);
            DataSource managed = managedDataSourceRegistry.lookup("default");
            assertThat(managed).isNotNull();

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.executeWithoutResult(status -> {
                InvocationContext ctx = normalContext();
                try {
                    realPropagationFilter().get().doFilter(ctx, chain -> {
                        try (Connection conn = managed.getConnection()) {
                            // 穿透激活：经受管代理拿到的必须是非物理关闭的复用代理
                            assertThat(conn).isInstanceOf(NonCloseableLingConnectionProxy.class);
                            try (Statement st = conn.createStatement()) {
                                st.execute("INSERT INTO " + E2E_TABLE + " (id, note) VALUES (1, 'reuse')");
                            }
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                } finally {
                    InvocationContext.detach(null);
                }
            });

            // 根事务正常提交：数据真实落库
            assertThat(jdbc(coreDataSource).queryForObject(
                    "SELECT COUNT(*) FROM " + E2E_TABLE + " WHERE id = 1", Integer.class)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("rollbackOnly 信号上行 → 根事务真实回滚")
    class RollbackOnlyPropagation {

        @Test
        @DisplayName("灵元内声明回滚 → 根 commit 抛 LingTransactionRollbackException → 数据不落库")
        void lingRollbackOnlyForcesRootRollback() throws Throwable {
            ensureTable(coreDataSource);
            DataSource managed = managedDataSourceRegistry.lookup("default");
            assertThat(managed).isNotNull();

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> template.executeWithoutResult(status -> {
                InvocationContext ctx = normalContext();
                try {
                    realPropagationFilter().get().doFilter(ctx, chain -> {
                        try (Connection conn = managed.getConnection()) {
                            try (Statement st = conn.createStatement()) {
                                st.execute("INSERT INTO " + E2E_TABLE + " (id, note) VALUES (2, 'rollback')");
                            }
                            // 灵元内回滚：NonCloseable 代理仅置 rollbackOnly 信号（快照合并语义上行）
                            conn.rollback();
                        }
                        return null;
                    });
                } catch (LingTransactionRollbackException e) {
                    // 回滚信号异常是预期路径：原样透传（RuntimeException），
                    // 让 TransactionTemplate 感知回滚意图并真实回滚根事务
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                } finally {
                    InvocationContext.detach(null);
                }
            })).isInstanceOf(LingTransactionRollbackException.class);

            // 根事务真实回滚：灵元写入的数据不落库
            assertThat(jdbc(coreDataSource).queryForObject(
                    "SELECT COUNT(*) FROM " + E2E_TABLE + " WHERE id = 2", Integer.class)).isZero();
        }
    }

    @Nested
    @DisplayName("灵元异常 → 根事务回滚")
    class LingExceptionRollback {

        @Test
        @DisplayName("灵元 Mapper 抛异常 → 根事务回滚 → 部分写入不落库")
        void lingExceptionRollsBackRootTransaction() {
            ensureTable(coreDataSource);
            DataSource managed = managedDataSourceRegistry.lookup("default");
            assertThat(managed).isNotNull();

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> template.executeWithoutResult(status -> {
                InvocationContext ctx = normalContext();
                try {
                    realPropagationFilter().get().doFilter(ctx, chain -> {
                        try (Connection conn = managed.getConnection()) {
                            try (Statement st = conn.createStatement()) {
                                st.execute("INSERT INTO " + E2E_TABLE + " (id, note) VALUES (3, 'boom')");
                            }
                        }
                        throw new IllegalStateException("ling mapper failure");
                    });
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                } finally {
                    InvocationContext.detach(null);
                }
            })).isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(IllegalStateException.class);

            // 根事务因异常回滚：已执行的 INSERT 一并回滚
            assertThat(jdbc(coreDataSource).queryForObject(
                    "SELECT COUNT(*) FROM " + E2E_TABLE + " WHERE id = 3", Integer.class)).isZero();
        }
    }
}
