package com.lingframe.api.storage;

import javax.sql.DataSource;

/**
 * 受管数据源供给 SPI：灵核（模式 1）或存储灵元（模式 3）向微内核总线供给受管数据源。
 * <p>
 * 数据源持有方与消费方（业务灵元）解耦：灵元零 JDBC 配置，经
 * {@link ManagedDataSourceRegistry#lookup(String)} 按 dataSourceId 获取代理。
 * 默认 dataSourceId 为 "default"。
 */
public interface ManagedDataSourceProvider {

    /**
     * 获取受管数据源（灵核侧通常为 {@code DataSourceWrapperProcessor} 包装后的治理代理）。
     *
     * @return 受管数据源
     */
    DataSource getDataSource();

    /**
     * 受管数据源身份标识。
     * <p>
     * 模式 1 灵核供给端恒为 {@code "default"}；模式 3 存储灵元供给端为各自声明的
     * dataSourceId（多个存储灵元同时挂载时以此区分）。
     *
     * @return 数据源 ID
     */
    default String getDataSourceId() {
        return "default";
    }
}
