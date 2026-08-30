package com.lingframe.infra.mybatisplus;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import com.lingframe.infra.storage.proxy.NonCloseableLingConnectionProxy;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * MyBatis-Plus 拦截器经受管代理集成兼容测试。
 * <p>
 * 验证主流 ORM 拦截器链（分页 + 多租户）与 {@link LingDataSourceProxy} /
 * {@link NonCloseableLingConnectionProxy} 的双层代理兼容性：
 * 拦截器拿到的连接视图、分页改写、租户条件注入在治理代理下均不失效，
 * 且穿透命中时 Mapper 查询复用穿透连接（NonCloseable 视图修正不被破坏）。
 */
@DisplayName("MyBatis-Plus 拦截器经受管代理兼容")
class MybatisPlusInterceptorProxyCompatibilityTest {

    private static final String TENANT_COLUMN = "tenant_id";
    private static final long TENANT_ONE = 1L;
    private static final long TENANT_TWO = 2L;

    private DataSource rawDataSource;
    private LingDataSourceProxy managedProxy;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        rawDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:lingframe-mp-compat;DB_CLOSE_DELAY=-1", "sa", "");
        managedProxy = new LingDataSourceProxy(rawDataSource, mock(PermissionService.class), "default");

        createTableAndSeed();

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 多租户拦截器先于分页：租户条件需注入到 count 与分页两条 SQL
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new LongValue(TENANT_ONE);
            }

            @Override
            public String getTenantIdColumn() {
                return TENANT_COLUMN;
            }
        }));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("lingframe-mp-test", new JdbcTransactionFactory(), managedProxy));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(DemoOrderMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    private void createTableAndSeed() throws Exception {
        try (Connection conn = rawDataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS demo_order ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "tenant_id BIGINT NOT NULL, "
                    + "note VARCHAR(32))");
            st.execute("DELETE FROM demo_order");
            st.execute("INSERT INTO demo_order (tenant_id, note) VALUES (1, 't1-a')");
            st.execute("INSERT INTO demo_order (tenant_id, note) VALUES (1, 't1-b')");
            st.execute("INSERT INTO demo_order (tenant_id, note) VALUES (1, 't1-c')");
            st.execute("INSERT INTO demo_order (tenant_id, note) VALUES (2, 't2-x')");
        }
    }

    @Nested
    @DisplayName("分页拦截器")
    class PaginationInterceptorCompatibility {

        @Test
        @DisplayName("经受管代理执行分页查询：total 与当前页数据正确（分页改写不被治理代理破坏）")
        void paginationWorksThroughManagedProxy() {
            try (SqlSession session = sqlSessionFactory.openSession()) {
                DemoOrderMapper mapper = session.getMapper(DemoOrderMapper.class);
                Page<DemoOrder> page = mapper.selectPage(new Page<DemoOrder>(1, 2), null);

                // 多租户过滤后共 3 条（tenant 1），分页取第 1 页 2 条
                assertEquals(3, page.getTotal());
                assertEquals(2, page.getRecords().size());
            }
        }

        @Test
        @DisplayName("第二页数据正确（分页 offset 生效）")
        void secondPageWorks() {
            try (SqlSession session = sqlSessionFactory.openSession()) {
                DemoOrderMapper mapper = session.getMapper(DemoOrderMapper.class);
                Page<DemoOrder> page = mapper.selectPage(new Page<DemoOrder>(2, 2), null);

                assertEquals(3, page.getTotal());
                assertEquals(1, page.getRecords().size());
            }
        }
    }

    @Nested
    @DisplayName("多租户拦截器")
    class TenantInterceptorCompatibility {

        @Test
        @DisplayName("经受管代理执行全表查询：租户条件自动注入，仅返回当前租户数据")
        void tenantConditionInjected() {
            try (SqlSession session = sqlSessionFactory.openSession()) {
                DemoOrderMapper mapper = session.getMapper(DemoOrderMapper.class);
                List<DemoOrder> all = mapper.selectList(null);

                // tenant 2 的数据被拦截器过滤，只剩 tenant 1 的三条
                assertEquals(3, all.size());
                for (DemoOrder order : all) {
                    assertEquals(TENANT_ONE, order.getTenantId());
                }
            }
        }
    }

    @Nested
    @DisplayName("穿透命中：NonCloseable 视图修正")
    class PenetrationViewCorrection {

        @Test
        @DisplayName("穿透栈命中时 Mapper 分页查询复用穿透连接，NonCloseable 代理下拦截器仍正常工作")
        void penetrationHitKeepsInterceptorsWorking() throws Exception {
            // 模拟根事务连接已压栈（穿透激活）
            try (Connection root = rawDataSource.getConnection()) {
                LingTransactionContext.pushConnection("default", root);
                try (Connection conn = managedProxy.getConnection()) {
                    assertInstanceOf(NonCloseableLingConnectionProxy.class, conn);
                }

                try (SqlSession session = sqlSessionFactory.openSession()) {
                    DemoOrderMapper mapper = session.getMapper(DemoOrderMapper.class);
                    Page<DemoOrder> page = mapper.selectPage(new Page<DemoOrder>(1, 2), null);

                    // 分页与租户拦截器在穿透连接上依旧生效
                    assertEquals(3, page.getTotal());
                    assertEquals(2, page.getRecords().size());
                }
                LingTransactionContext.popConnection();
            }
        }

        @Test
        @DisplayName("NonCloseable 视图修正：Statement.getConnection 返回本代理而非内层连接")
        void statementConnectionViewIsCorrected() throws Exception {
            try (Connection root = rawDataSource.getConnection()) {
                LingTransactionContext.pushConnection("default", root);
                try (Connection conn = managedProxy.getConnection()) {
                    assertInstanceOf(NonCloseableLingConnectionProxy.class, conn);
                    try (Statement st = conn.createStatement()) {
                        // 视图修正：Statement 拿到的连接视图仍是不可物理关闭的代理
                        assertInstanceOf(NonCloseableLingConnectionProxy.class, st.getConnection());
                        assertNotNull(st.getConnection());
                    }
                }
                LingTransactionContext.popConnection();
            }
        }
    }

    @TableName("demo_order")
    static class DemoOrder {
        @TableId(type = IdType.AUTO)
        private Long id;
        private Long tenantId;
        private String note;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    interface DemoOrderMapper extends BaseMapper<DemoOrder> {
    }
}
