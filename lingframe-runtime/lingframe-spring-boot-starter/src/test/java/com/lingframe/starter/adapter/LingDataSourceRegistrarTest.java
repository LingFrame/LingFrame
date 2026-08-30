package com.lingframe.starter.adapter;

import com.lingframe.api.storage.ManagedDataSourceRegistry;
import com.lingframe.starter.storage.DefaultManagedDataSourceRegistry;
import com.lingframe.starter.transaction.LingManagedTransactionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 灵元数据源自动注册器测试：分支 A（独立库）/ 分支 B（受管共享）决策树语义。
 */
@DisplayName("LingDataSourceRegistrar 数据源决策树")
class LingDataSourceRegistrarTest {

    @Nested
    @DisplayName("分支 B：受管共享（模式 1/3）")
    class BranchB {

        @Test
        @DisplayName("无 url + 总线提供 default → 注入受管数据源并标记 @Primary")
        void injectsManagedDataSourceAsPrimary() {
            GenericApplicationContext context = new GenericApplicationContext();
            context.setEnvironment(new MockEnvironment());

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            DataSource managed = mock(DataSource.class);
            when(registry.lookup("default")).thenReturn(managed);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", registry);

            assertThat(context.containsBeanDefinition("dataSource")).isTrue();
            BeanDefinition bd = context.getBeanDefinition("dataSource");
            assertThat(bd.isPrimary()).isTrue();
        }

        @Test
        @DisplayName("无 url + 总线为 null → 跳过注入（灵核 0 存储/native 场景）")
        void skipsWhenRegistryUnavailable() {
            GenericApplicationContext context = new GenericApplicationContext();
            context.setEnvironment(new MockEnvironment());

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", null);

            assertThat(context.containsBeanDefinition("dataSource")).isFalse();
        }

        @Test
        @DisplayName("无 url + 总线无目标 dataSourceId → 跳过注入并告警")
        void skipsWhenTargetMissing() {
            GenericApplicationContext context = new GenericApplicationContext();
            context.setEnvironment(new MockEnvironment());

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            when(registry.lookup("default")).thenReturn(null);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", registry);

            assertThat(context.containsBeanDefinition("dataSource")).isFalse();
        }

        @Test
        @DisplayName("无 url + 总线有数据源 + 穿透总开关关闭 → 注入数据源但不注册事务管理器（应急降级）")
        void injectsDataSourceWhenPropagationDisabled() {
            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment env = new MockEnvironment();
            env.setProperty("lingframe.tx.propagation.enabled", "false");
            context.setEnvironment(env);

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            DataSource managed = mock(DataSource.class);
            when(registry.lookup("default")).thenReturn(managed);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", registry);

            // 数据源仍注入（业务可读写），事务管理器注册点被跳过——灵元退回独立连接心智
            assertThat(context.containsBeanDefinition("dataSource")).isTrue();
            assertThat(context.containsBeanDefinition("lingTransactionManager")).isFalse();
        }

        @Test
        @DisplayName("无 url + 总线有数据源 + 穿透总开关开启（默认）→ 注入数据源并注册受管事务管理器")
        void registersTransactionManagerWhenPropagationEnabled() {
            GenericApplicationContext context = new GenericApplicationContext();
            context.setEnvironment(new MockEnvironment());

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            DataSource managed = mock(DataSource.class);
            when(registry.lookup("default")).thenReturn(managed);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", registry);

            // 穿透开启：数据源 + 受管双路径事务管理器都注册（强一致性路径）
            assertThat(context.containsBeanDefinition("dataSource")).isTrue();
            assertThat(context.containsBeanDefinition("lingTransactionManager")).isTrue();
        }

        @Test
        @DisplayName("refresh 后事务管理器 Bean 实例化为 LingManagedTransactionManager 且 dataSourceId 正确")
        void transactionManagerInstantiatesWithCorrectDataSourceId() {
            GenericApplicationContext context = new GenericApplicationContext();
            context.setEnvironment(new MockEnvironment());

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            DataSource managed = mock(DataSource.class);
            when(registry.lookup("default")).thenReturn(managed);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", registry);
            context.refresh();
            try {
                // 延迟求值的 Supplier 在 refresh 时才真正实例化事务管理器
                LingManagedTransactionManager txManager =
                        context.getBean("lingTransactionManager", LingManagedTransactionManager.class);
                assertThat(txManager.getDataSourceId()).isEqualTo("default");
            } finally {
                context.close();
            }
        }

        @Test
        @DisplayName("无 url + 配置 lingframe.ling.datasource-ref 自定义 ID → 按自定义 ID 从总线拉取")
        void injectsByCustomDataSourceRef() {
            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment env = new MockEnvironment();
            // 模式 3 多存储灵元场景：灵元显式声明使用 "order-ds" 而非默认 "default"
            env.setProperty("lingframe.ling.datasource-ref", "order-ds");
            context.setEnvironment(env);

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            DataSource managed = mock(DataSource.class);
            when(registry.lookup("order-ds")).thenReturn(managed);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", registry);

            // 总线按自定义 ID 查询，而非默认 "default"
            verify(registry).lookup("order-ds");
            verify(registry, never()).lookup("default");
            assertThat(context.containsBeanDefinition("dataSource")).isTrue();
        }

        @Test
        @DisplayName("无 url + 自定义 datasource-ref 在总线缺失 → 跳过注入并告警")
        void skipsWhenCustomRefMissing() {
            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment env = new MockEnvironment();
            env.setProperty("lingframe.ling.datasource-ref", "ghost-ds");
            context.setEnvironment(env);

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            when(registry.lookup("ghost-ds")).thenReturn(null);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "demo-ling", registry);

            verify(registry).lookup("ghost-ds");
            assertThat(context.containsBeanDefinition("dataSource")).isFalse();
        }
    }

    @Nested
    @DisplayName("分支 A：独立库（模式 2）")
    class BranchA {

        @Test
        @DisplayName("配置 spring.datasource.url → 维持自建独立连接池")
        void registersIsolatedDataSource() {
            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment env = new MockEnvironment();
            env.setProperty("spring.datasource.url", "jdbc:h2:mem:isolated;DB_CLOSE_DELAY=-1");
            env.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
            context.setEnvironment(env);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "isolated-ling", null);

            assertThat(context.containsBeanDefinition("dataSource")).isTrue();
            assertThat(context.containsBeanDefinition("lingDataSourceProperties")).isTrue();
        }

        @Test
        @DisplayName("auto-datasource 总开关关闭 → 分支 A/B 均不装配")
        void respectsAutoDataSourceSwitch() {
            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment env = new MockEnvironment();
            env.setProperty("lingframe.ling.auto-datasource", "false");
            env.setProperty("spring.datasource.url", "jdbc:h2:mem:isolated;DB_CLOSE_DELAY=-1");
            context.setEnvironment(env);

            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "disabled-ling", null);

            assertThat(context.containsBeanDefinition("dataSource")).isFalse();
        }

        @Test
        @DisplayName("分支 A + 声明 lingframe.ling.datasource-id → 存储灵元把自建数据源注册到受管总线（模式 3 供给端）")
        void registersManagedSupplierWhenDataSourceIdDeclared() {
            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment env = new MockEnvironment();
            env.setProperty("spring.datasource.url", "jdbc:h2:mem:isolated;DB_CLOSE_DELAY=-1");
            env.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
            env.setProperty("lingframe.ling.datasource-id", "infra-order-ds");
            context.setEnvironment(env);

            ManagedDataSourceRegistry registry = new DefaultManagedDataSourceRegistry();
            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "storage-ling", registry);

            // 自建独立池 + 供给注册到总线（只增不减：注册后不触发 unregister）。
            // 供给 lambda 在 lookup 时才延迟取 dataSource Bean（refresh 后），此处只验证注册点；
            // 真实时序（refresh 后消费）的连接复用由 ManagedAssemblyChainContractTest 覆盖
            assertThat(context.containsBeanDefinition("dataSource")).isTrue();
            assertThat(registry.getDataSourceIds()).contains("infra-order-ds");
        }

        @Test
        @DisplayName("分支 A + 未声明 datasource-id → 不注册供给（普通业务灵元自建池不进入总线）")
        void doesNotRegisterSupplierWithoutDataSourceId() {
            GenericApplicationContext context = new GenericApplicationContext();
            MockEnvironment env = new MockEnvironment();
            env.setProperty("spring.datasource.url", "jdbc:h2:mem:isolated;DB_CLOSE_DELAY=-1");
            env.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
            context.setEnvironment(env);

            ManagedDataSourceRegistry registry = new DefaultManagedDataSourceRegistry();
            LingDataSourceRegistrar.register(context, getClass().getClassLoader(), "biz-ling", registry);

            assertThat(registry.getDataSourceIds()).isEmpty();
        }
    }
}
