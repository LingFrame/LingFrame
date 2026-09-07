package com.lingframe.infra.mybatisplus;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TableNameHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * MyBatis-Plus 高级插件经受管代理集成兼容测试。
 * <p>
 * 在基础分页/多租户兼容之上，验证主流高级插件与 {@link LingDataSourceProxy}
 * 双层代理的兼容性：
 * <ul>
 *   <li><b>乐观锁</b>（{@link OptimisticLockerInnerInterceptor} + {@code @Version}）：
 *       更新自动携带版本条件并递增，并发写被版本条件拦截；</li>
 *   <li><b>动态表名</b>（{@link DynamicTableNameInnerInterceptor} + {@link TableNameHandler}）：
 *       查询自动改写目标表名，分表/影子表场景生效；</li>
 *   <li><b>逻辑删除</b>（{@code @TableLogic}）：删除转为置位标记，查询自动过滤已删行。</li>
 * </ul>
 * 三层代理链：MyBatis-Plus 拦截器 → 治理代理（LingDataSourceProxy）→ 底层连接池，
 * 任一环断裂都会让本测试红。
 */
@DisplayName("MyBatis-Plus 高级插件经受管代理兼容")
class MybatisPlusAdvancedPluginCompatibilityTest {

    private SqlSessionFactory optimisticSqlSessionFactory;
    private SqlSessionFactory dynamicTableSqlSessionFactory;
    private SqlSessionFactory logicDeleteSqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        DataSource raw = new DriverManagerDataSource(
                "jdbc:h2:mem:lingframe-mp-adv;DB_CLOSE_DELAY=-1", "sa", "");
        LingDataSourceProxy managed = new LingDataSourceProxy(raw, mock(PermissionService.class), "default");

        try (Connection conn = raw.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS adv_product ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(64), "
                    + "version INT NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS adv_product_archive ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(64), "
                    + "version INT NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS adv_order ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "note VARCHAR(64), "
                    + "deleted INT NOT NULL DEFAULT 0)");
            st.execute("DELETE FROM adv_product");
            st.execute("DELETE FROM adv_product_archive");
            st.execute("DELETE FROM adv_order");
            st.execute("INSERT INTO adv_product (name, version) VALUES ('p1', 0)");
            st.execute("INSERT INTO adv_product (name, version) VALUES ('p2', 0)");
            st.execute("INSERT INTO adv_product_archive (name) VALUES ('archived-1')");
            st.execute("INSERT INTO adv_order (note, deleted) VALUES ('o1', 0)");
            st.execute("INSERT INTO adv_order (note, deleted) VALUES ('o2', 1)");
        }

        optimisticSqlSessionFactory = buildFactory(managed, OptimisticLockerInnerInterceptor.class);
        dynamicTableSqlSessionFactory = buildFactory(managed, DynamicTableNameInnerInterceptor.class);
        logicDeleteSqlSessionFactory = buildFactory(managed, null);
    }

    /**
     * 构建带指定高级拦截器的 SqlSessionFactory（经受管代理）。
     * 动态表名拦截器用 lambda 表名处理器指向 archive 表；其余场景无拦截器。
     */
    private SqlSessionFactory buildFactory(LingDataSourceProxy managed, Class<?> interceptorType) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("lingframe-mp-adv", new JdbcTransactionFactory(), managed));

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        if (OptimisticLockerInnerInterceptor.class.equals(interceptorType)) {
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        } else if (DynamicTableNameInnerInterceptor.class.equals(interceptorType)) {
            DynamicTableNameInnerInterceptor dynamic = new DynamicTableNameInnerInterceptor();
            dynamic.setTableNameHandler((sql, tableName) ->
                    "adv_product".equals(tableName) ? "adv_product_archive" : tableName);
            interceptor.addInnerInterceptor(dynamic);
        }
        configuration.addInterceptor(interceptor);
        configuration.addMapper(ProductMapper.class);
        configuration.addMapper(OrderMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    @Nested
    @DisplayName("乐观锁插件")
    class OptimisticLocker {

        @Test
        @DisplayName("updateById 自动携带版本条件并递增；携带旧版本更新不生效（并发写被拦截）")
        void optimisticLockGuardsConcurrentWrites() {
            try (SqlSession session = optimisticSqlSessionFactory.openSession()) {
                ProductMapper mapper = session.getMapper(ProductMapper.class);
                // 用 selectList 取实际主键（AUTO_INCREMENT 序列在 DELETE 后不重置，不能用固定 id）
                Product product = mapper.selectList(null).get(0);
                assertNotNull(product);
                assertEquals(0, product.getVersion());

                // 第一轮更新：version 条件匹配，更新成功并递增
                product.setName("p1-v1");
                assertEquals(1, mapper.updateById(product));
                assertEquals(1, product.getVersion());

                // 模拟并发旧版本：用旧 version 再更新，应被乐观锁拦截（影响行数为 0）
                Product stale = mapper.selectList(null).get(0);
                stale.setName("p1-stale");
                stale.setVersion(0); // 人为回退到旧版本，模拟读到过期数据
                assertEquals(0, mapper.updateById(stale));

                // 库中仍是最新版本的数据
                Product latest = mapper.selectList(null).get(0);
                assertEquals("p1-v1", latest.getName());
                assertEquals(1, latest.getVersion());
            }
        }
    }

    @Nested
    @DisplayName("动态表名插件")
    class DynamicTableName {

        @Test
        @DisplayName("查询经受管代理自动改写表名：主表查询实际命中 archive 表（分表/影子表生效）")
        void dynamicTableNameRoutesToArchive() {
            try (SqlSession session = dynamicTableSqlSessionFactory.openSession()) {
                ProductMapper mapper = session.getMapper(ProductMapper.class);
                List<Product> all = mapper.selectList(null);

                // 表名被改写到 adv_product_archive，命中归档数据而非主表
                assertEquals(1, all.size());
                assertEquals("archived-1", all.get(0).getName());
            }
        }
    }

    @Nested
    @DisplayName("逻辑删除插件")
    class LogicDelete {

        @Test
        @DisplayName("deleteById 转为置位标记；查询自动过滤已删行（逻辑删除语义经受管代理生效）")
        void logicDeleteMarksAndFilters() {
            try (SqlSession session = logicDeleteSqlSessionFactory.openSession()) {
                OrderMapper mapper = session.getMapper(OrderMapper.class);

                // 初始：deleted=1 的 o2 已被过滤，仅剩 o1
                List<Order> before = mapper.selectList(null);
                assertEquals(1, before.size());
                assertEquals("o1", before.get(0).getNote());

                // 逻辑删除 o1：delete 转 update 置位
                assertEquals(1, mapper.deleteById(before.get(0).getId()));

                // 删除后查询为空（已删行被自动过滤）
                List<Order> after = mapper.selectList(null);
                assertEquals(0, after.size());
            }
        }
    }

    @TableName("adv_product")
    static class Product {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String name;
        @Version
        private Integer version;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }
    }

    @TableName("adv_order")
    static class Order {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String note;
        @TableLogic
        private Integer deleted;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public Integer getDeleted() {
            return deleted;
        }

        public void setDeleted(Integer deleted) {
            this.deleted = deleted;
        }
    }

    interface ProductMapper extends BaseMapper<Product> {
    }

    interface OrderMapper extends BaseMapper<Order> {
    }
}