package com.lingframe.starter.transaction;

import com.lingframe.api.storage.ManagedDataSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事务状态提取 SPI 的 Spring 实现测试：TSM 活跃事务判定 / 按受管代理实例提取绑定连接 /
 * 活跃绑定源集合判定（JPA 根无资源键 → 空集，穿透不激活）。
 */
@DisplayName("SpringTransactionBindingHook 事务状态提取")
class SpringTransactionBindingHookTest {

    private static final String DEFAULT_ID = "default";

    @AfterEach
    void tearDown() {
        // 清理 Spring 静态 ThreadLocal，防止跨用例污染；
        // clearSynchronization 在无活跃同步时抛异常，须先判活
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    /**
     * 模拟真实活跃事务：开启同步 + 置位 actualTransactionActive（Spring 5.1+ API）。
     * 仅 initSynchronization 不足以让 isActualTransactionActive() 返回 true。
     */
    private void beginActiveTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private SpringTransactionBindingHook hookWith(ManagedDataSourceRegistry registry) {
        return new SpringTransactionBindingHook(registry);
    }

    @Nested
    @DisplayName("活跃事务判定")
    class TransactionActive {

        @Test
        @DisplayName("无活跃事务时 isTransactionActive 返回 false")
        void inactiveWhenNoSynchronization() {
            SpringTransactionBindingHook hook = hookWith(mock(ManagedDataSourceRegistry.class));
            assertFalse(hook.isTransactionActive());
        }

        @Test
        @DisplayName("TSM 同步激活后 isTransactionActive 返回 true")
        void activeWhenSynchronizationInitialized() {
            beginActiveTransaction();
            SpringTransactionBindingHook hook = hookWith(mock(ManagedDataSourceRegistry.class));
            assertTrue(hook.isTransactionActive());
        }
    }

    @Nested
    @DisplayName("绑定连接提取")
    class BoundConnection {

        @Test
        @DisplayName("按受管代理实例为 TSM 资源键提取 ConnectionHolder 内的连接")
        void extractsBoundConnection() {
            DataSource managedProxy = mock(DataSource.class);
            Connection bound = mock(Connection.class);

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            when(registry.lookup(DEFAULT_ID)).thenReturn(managedProxy);

            beginActiveTransaction();
            TransactionSynchronizationManager.bindResource(managedProxy, new ConnectionHolder(bound));

            SpringTransactionBindingHook hook = hookWith(registry);
            assertSame(bound, hook.getBoundConnection(DEFAULT_ID));
        }

        @Test
        @DisplayName("该源未绑定到 TSM 时提取 null（JPA 根场景）")
        void nullWhenSourceNotBound() {
            DataSource managedProxy = mock(DataSource.class);
            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            when(registry.lookup(DEFAULT_ID)).thenReturn(managedProxy);

            beginActiveTransaction();
            // 不 bind 任何资源——模拟 JPA 根：物理连接封装在 EntityManager 内，无 DataSource 资源键

            SpringTransactionBindingHook hook = hookWith(registry);
            assertNull(hook.getBoundConnection(DEFAULT_ID));
        }

        @Test
        @DisplayName("总线无该 dataSourceId 时提取 null")
        void nullWhenDataSourceIdMissing() {
            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            when(registry.lookup(DEFAULT_ID)).thenReturn(null);

            beginActiveTransaction();

            SpringTransactionBindingHook hook = hookWith(registry);
            assertNull(hook.getBoundConnection(DEFAULT_ID));
        }
    }

    @Nested
    @DisplayName("活跃绑定源集合判定")
    class ActiveBoundSources {

        @Test
        @DisplayName("命中 TSM 资源的源进入活跃集合（模式 3 多源逐源判定）")
        void collectsOnlyBoundSources() {
            DataSource orderProxy = mock(DataSource.class);
            DataSource userProxy = mock(DataSource.class);

            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            Set<String> registered = new LinkedHashSet<>();
            registered.add("order-ds");
            registered.add("user-ds");
            when(registry.getDataSourceIds()).thenReturn(registered);
            when(registry.lookup("order-ds")).thenReturn(orderProxy);
            when(registry.lookup("user-ds")).thenReturn(userProxy);

            beginActiveTransaction();
            // 仅 order-ds 绑定（user-ds 未绑定 → 不入活跃集合）
            TransactionSynchronizationManager.bindResource(orderProxy, new ConnectionHolder(mock(Connection.class)));

            SpringTransactionBindingHook hook = hookWith(registry);
            Set<String> active = hook.getActiveBoundDataSourceIds();

            assertTrue(active.contains("order-ds"));
            assertFalse(active.contains("user-ds"));
        }

        @Test
        @DisplayName("无活跃事务时活跃绑定源集合为空集")
        void emptyWhenNoTransaction() {
            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            when(registry.getDataSourceIds()).thenReturn(Collections.singleton(DEFAULT_ID));

            SpringTransactionBindingHook hook = hookWith(registry);
            assertTrue(hook.getActiveBoundDataSourceIds().isEmpty());
        }

        @Test
        @DisplayName("活跃事务但全部源未绑定（JPA 根）→ 空集，穿透不激活")
        void emptyWhenNothingBound() {
            DataSource orderProxy = mock(DataSource.class);
            ManagedDataSourceRegistry registry = mock(ManagedDataSourceRegistry.class);
            when(registry.getDataSourceIds()).thenReturn(Collections.singleton("order-ds"));
            when(registry.lookup("order-ds")).thenReturn(orderProxy);

            beginActiveTransaction();
            // 不 bind 任何资源——JPA 根全部 miss

            SpringTransactionBindingHook hook = hookWith(registry);
            assertTrue(hook.getActiveBoundDataSourceIds().isEmpty());
        }
    }
}
