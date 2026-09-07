package com.lingframe.starter.storage;

import com.lingframe.api.storage.ManagedDataSourceProvider;
import com.lingframe.api.storage.ManagedDataSourceRegistry;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 受管数据源独立总线默认实现（runtime starter）。
 * <p>
 * 简单并发 Map 实现：以 dataSourceId 为键保存供给者，仅做注册/查找/反注册，
 * 不承载任何服务契约语义。灵核侧（模式 1）与存储灵元侧（模式 3）共用本实现。
 * <p>
 * 生命周期语义（基础设施只增不减）：基础设施灵元（模式 3）本期不提供
 * 热卸载，其注册后不触发 {@link #unregister}；unregister 保留为运维停用/未来能力的
 * API 预留。
 */
public class DefaultManagedDataSourceRegistry implements ManagedDataSourceRegistry {

    private final ConcurrentMap<String, ManagedDataSourceProvider> providers = new ConcurrentHashMap<>();

    @Override
    public void register(String dataSourceId, ManagedDataSourceProvider provider) {
        if (dataSourceId == null || dataSourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("dataSourceId must not be empty");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        providers.put(dataSourceId, provider);
    }

    @Override
    public void unregister(String dataSourceId) {
        // null 直接作为 ConcurrentHashMap.remove 键会抛 NPE，入口处显式拒绝以固化契约边界
        Objects.requireNonNull(dataSourceId, "dataSourceId must not be null");
        providers.remove(dataSourceId);
    }

    @Override
    public DataSource lookup(String dataSourceId) {
        ManagedDataSourceProvider provider = dataSourceId == null ? null : providers.get(dataSourceId);
        return provider == null ? null : provider.getDataSource();
    }

    @Override
    public Set<String> getDataSourceIds() {
        return Collections.unmodifiableSet(providers.keySet());
    }
}
