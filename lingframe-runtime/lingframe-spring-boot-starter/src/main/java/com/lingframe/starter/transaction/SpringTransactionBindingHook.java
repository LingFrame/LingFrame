package com.lingframe.starter.transaction;

import com.lingframe.api.storage.ManagedDataSourceRegistry;
import com.lingframe.core.spi.TransactionBindingHook;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 事务状态提取 SPI 的 Spring 实现（只进 runtime，core 零 Spring）。
 * <p>
 * 基于 {@link TransactionSynchronizationManager}：按受管代理实例（TSM 资源键）提取绑定连接。
 * 提取键 = 受管 {@code LingDataSourceProxy} 实例——{@code DataSourceWrapperProcessor} 保证
 * 灵核 {@code DataSourceTransactionManager} 持有的即代理，{@code ConnectionHolder} 内即
 * 治理代理视图。
 * <p>
 * 边界：JPA 根（{@code JpaTransactionManager}）物理连接封装在 EntityManager 内，
 * 无 DataSource 资源键 → 提取 null → 活跃绑定源集合为空 → 穿透不激活。
 */
public class SpringTransactionBindingHook implements TransactionBindingHook {

    private final ManagedDataSourceRegistry registry;

    public SpringTransactionBindingHook(ManagedDataSourceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean isTransactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public Set<String> getActiveBoundDataSourceIds() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return Collections.emptySet();
        }
        // 遍历总线上已注册的受管代理，逐个查 TSM 资源（键 = 受管代理实例），
        // 命中 ConnectionHolder 即视为该源已绑定；JPA 根场景全部 miss -> 空集 -> 穿透不激活
        Set<String> active = new LinkedHashSet<>();
        for (String dataSourceId : registry.getDataSourceIds()) {
            if (getBoundConnection(dataSourceId) != null) {
                active.add(dataSourceId);
            }
        }
        return active;
    }

    @Override
    public Connection getBoundConnection(String dataSourceId) {
        DataSource managedProxy = registry.lookup(dataSourceId);
        if (managedProxy == null) {
            return null;
        }
        // TSM 资源键 = 受管 LingDataSourceProxy 实例；ConnectionHolder 内即治理代理视图
        Object resource = TransactionSynchronizationManager.getResource(managedProxy);
        if (resource instanceof ConnectionHolder) {
            return ((ConnectionHolder) resource).getConnection();
        }
        return null;
    }
}
