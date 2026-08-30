package com.lingframe.api.storage;

import javax.sql.DataSource;
import java.util.Set;

/**
 * 受管数据源独立总线。
 * <p>
 * 与 {@code LingServiceRegistry}（FQSID 服务契约目录）职责分离：
 * 服务注册表承载 FQSID → 方法签名/提供方权重的服务契约目录；
 * 本总线只承载 "dataSourceId → 受管 DataSource" 的基础设施引渡关系。
 * <p>
 * {@link #unregister} 是受管数据源生命周期管理 API；基础设施灵元（模式 3）本期不提供
 * 热卸载（只增不减），故基础设施路径不触发 unregister——其保留为运维停用与未来能力的
 * 预留入口。
 */
public interface ManagedDataSourceRegistry {

    /**
     * 注册受管数据源供给者。
     *
     * @param dataSourceId 数据源 ID（模式 1 为 "default"，模式 3 为存储灵元声明的 ID）
     * @param provider     数据源供给者
     */
    void register(String dataSourceId, ManagedDataSourceProvider provider);

    /**
     * 反注册受管数据源（生命周期管理 API；基础设施路径本期不触发）。
     *
     * @param dataSourceId 数据源 ID
     */
    void unregister(String dataSourceId);

    /**
     * 按数据源 ID 查找受管数据源。
     *
     * @param dataSourceId 数据源 ID
     * @return 受管数据源；未注册时返回 null
     */
    DataSource lookup(String dataSourceId);

    /**
     * 列出当前已注册的全部 dataSourceId。
     * <p>
     * 供 {@code TransactionBindingHook} 实现遍历总线逐源查 TSM 资源，
     * 判定活跃事务实际绑定的受管数据源身份集合。
     *
     * @return 已注册 dataSourceId 集合（不可变视图；无注册时为空集）
     */
    Set<String> getDataSourceIds();
}
