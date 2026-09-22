package com.lingframe.dashboard.storage;

import javax.sql.DataSource;

/**
 * 治理控制面专用的数据源包装类。
 * 职责：包装控制面的 SQLite 数据源，防止其作为通用 DataSource 泄露到 Spring 容器中，
 * 从而避免干扰灵核业务的常规数据源（如 H2/MySQL）的自动装配。
 */
public class DashboardDataSource {
    private final DataSource dataSource;

    public DashboardDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource getDataSource() {
        return dataSource;
    }
}
