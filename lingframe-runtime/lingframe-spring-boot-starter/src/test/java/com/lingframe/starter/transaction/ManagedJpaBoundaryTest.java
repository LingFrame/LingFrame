package com.lingframe.starter.transaction;

import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import com.lingframe.infra.storage.proxy.NonCloseableLingConnectionProxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 灵元侧 JPA 边界测试：灵元引入 {@code spring-boot-starter-data-jpa} 后与受管事务管理器的
 * 实际装配与连接语义行为（文档与代码均未声明支持，本测试实证边界事实）。
 * <p>
 * 模拟灵元子容器装配（与 {@code LingDataSourceRegistrar} 分支 B 一致）：
 * <ol>
 *   <li>受管数据源（{@code LingDataSourceProxy}）以 {@code @Primary} 注入子容器；</li>
 *   <li>注册 {@code lingTransactionManager}（{@link LingManagedTransactionManager}，bean 名与
 *       分支 B 注册一致）；</li>
 *   <li>JPA 自动配置（data-jpa 在 classpath）由 {@code @EnableAutoConfiguration} 触发。</li>
 * </ol>
 * 验证点：
 * <ul>
 *   <li><b>双事务管理器行为</b>：已注册 {@code lingTransactionManager} 时，JPA 自动配置的
 *       {@code JpaTransactionManager} 是否被 {@code @ConditionalOnMissingBean} 抑制
 *       （无 {@code NoUniqueBeanDefinitionException} 歧义）；</li>
 *   <li><b>EntityManagerFactory 数据源身份</b>：EMF 基于受管代理构建（穿透可命中）；</li>
 *   <li><b>穿透命中时 Hibernate 连接语义</b>：经受管代理拿到的连接为
 *       {@link NonCloseableLingConnectionProxy}——commit/setAutoCommit 降级为 no-op，
 *       与 Hibernate 物理连接管理语义的交互如实断言。</li>
 * </ul>
 */
@SpringBootTest(classes = {ManagedJpaBoundaryTest.BoundaryJpaConfig.class}, properties = {
        "lingframe.enabled=true",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        // 显式方言：受管代理的元数据脱敏/包装会使 Hibernate 方言自动检测拿不到
        // DialectResolutionInfo（见 HibernateConnectionSemantics 边界测试），
        // 真实灵元侧 JPA 需显式配置方言——与本边界测试结论一致
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@DisplayName("灵元侧 JPA 边界：受管事务管理器 + data-jpa 装配与连接语义")
class ManagedJpaBoundaryTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void tearDown() {
        LingTransactionContext.clear();
    }

    /**
     * 模拟灵元子容器装配：受管代理 DataSource（@Primary）+ lingTransactionManager + JPA 自动配置。
     * 排除 DataSourceAutoConfiguration（避免与受管代理冲突），JPA 自动配置保持开启。
     * 注意：必须为普通 @Configuration 而非 @TestConfiguration——@SpringBootTest(classes=...)
     * 需要显式主配置类作为 @SpringBootConfiguration 候选。
     */
    @Configuration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class BoundaryJpaConfig {

        @Bean
        @Primary
        public DataSource dataSource(PermissionService permissionService) {
            DriverManagerDataSource raw = new DriverManagerDataSource();
            raw.setDriverClassName("org.h2.Driver");
            raw.setUrl("jdbc:h2:mem:lingframe-jpa-boundary;DB_CLOSE_DELAY=-1");
            raw.setUsername("sa");
            raw.setPassword("");
            return new LingDataSourceProxy(raw, permissionService, "default");
        }

        @Bean
        public PermissionService permissionService() {
            return mock(PermissionService.class);
        }

        @Bean
        public PlatformTransactionManager lingTransactionManager(DataSource dataSource) {
            // 与 LingDataSourceRegistrar 分支 B 注册的 bean 名一致
            return new LingManagedTransactionManager(dataSource, "default");
        }
    }

    @Nested
    @DisplayName("双事务管理器装配行为")
    class TransactionManagerAssembly {

        @Test
        @DisplayName("已注册 lingTransactionManager 时 JpaTransactionManager 被抑制：仅一个 PlatformTransactionManager，无歧义")
        void onlyOneTransactionManagerWhenLingManagedRegistered() {
            Map<String, PlatformTransactionManager> managers =
                    applicationContext.getBeansOfType(PlatformTransactionManager.class);

            // 实证 JpaBaseConfiguration.transactionManager 的 @ConditionalOnMissingBean：
            // 已有 PlatformTransactionManager（LingManagedTransactionManager）时 JpaTransactionManager 不装配
            assertThat(managers).hasSize(1);
            assertThat(managers).containsKey("lingTransactionManager");
            assertThat(managers.get("lingTransactionManager"))
                    .isInstanceOf(LingManagedTransactionManager.class);
        }

        @Test
        @DisplayName("按类型获取 PlatformTransactionManager 无歧义（@Autowired/TransactionTemplate 可解析）")
        void transactionManagerResolvesUnambiguously() {
            PlatformTransactionManager tm =
                    applicationContext.getBean(PlatformTransactionManager.class);
            assertThat(tm).isInstanceOf(LingManagedTransactionManager.class);

            // TransactionTemplate 可正常构造使用
            TransactionTemplate template = new TransactionTemplate(tm);
            assertThat(template).isNotNull();
        }
    }

    @Nested
    @DisplayName("EntityManagerFactory 装配与数据源身份")
    class EntityManagerFactoryIdentity {

        @Test
        @DisplayName("JPA 自动配置装配出 EntityManagerFactory，其数据源为受管代理（穿透可命中）")
        void entityManagerFactoryUsesManagedProxy() {
            assertThat(entityManagerFactory).isNotNull();

            // LocalContainerEntityManagerFactoryBean 的 DataSource 就是 @Primary 受管代理
            LocalContainerEntityManagerFactoryBean emfBean =
                    applicationContext.getBean(LocalContainerEntityManagerFactoryBean.class);
            assertThat(emfBean.getDataSource()).isInstanceOf(LingDataSourceProxy.class);

            // EntityManager 可创建（Hibernate 方言基于 H2 正常初始化）
            EntityManager em = entityManagerFactory.createEntityManager();
            try {
                assertThat(em.isOpen()).isTrue();
            } finally {
                em.close();
            }
        }
    }

    @Nested
    @DisplayName("穿透命中时 Hibernate 连接语义")
    class HibernateConnectionSemantics {

        @Test
        @DisplayName("穿透栈非空时经受管代理 getConnection 返回 NonCloseable 代理（Hibernate 拿到的连接 commit/setAutoCommit 被降级）")
        void managedProxyReturnsNonCloseableWhenStackHit() throws Exception {
            DataSource managed = applicationContext.getBean("dataSource", DataSource.class);
            assertThat(managed).isInstanceOf(LingDataSourceProxy.class);

            // 模拟穿透命中：根事务连接已压栈
            try (Connection root = managed.getConnection()) {
                LingTransactionContext.pushConnection("default", root);

                try (Connection conn = managed.getConnection()) {
                    // 穿透命中 -> NonCloseable：close/commit/setAutoCommit no-op，rollback 仅置信号
                    assertThat(conn).isInstanceOf(NonCloseableLingConnectionProxy.class);

                    // Hibernate 的物理连接管理调用（setAutoCommit(false) / commit）不抛异常但物理不生效
                    conn.setAutoCommit(false);   // no-op，不抛
                    conn.commit();               // no-op，不抛（物理提交权归根事务）
                    conn.close();                // no-op，不归还池（生命周期归根事务）

                    // rollback 触发回滚信号（快照合并语义上行）
                    conn.rollback();
                    assertThat(LingTransactionContext.isRollbackOnly()).isTrue();
                } finally {
                    LingTransactionContext.popConnection("default");
                }
            }
        }

        @Test
        @DisplayName("穿透栈空时经受管代理 getConnection 返回普通治理代理（Hibernate 独立连接心智，不与根事务协调）")
        void managedProxyReturnsPlainProxyWhenNoStackHit() throws Exception {
            DataSource managed = applicationContext.getBean("dataSource", DataSource.class);

            try (Connection conn = managed.getConnection()) {
                // 栈空（非根事务内 Hibernate 自行取连接）-> 普通治理代理
                assertThat(conn).isNotInstanceOf(NonCloseableLingConnectionProxy.class);
                assertThat(LingTransactionContext.hasAnyConnection()).isFalse();
            }
        }
    }
}
