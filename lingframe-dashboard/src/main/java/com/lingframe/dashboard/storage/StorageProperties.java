package com.lingframe.dashboard.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQLite 存储配置属性
 */
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.storage")
public class StorageProperties {

    /**
     * 是否启用持久化存储
     */
    private boolean enabled = true;

    /**
     * SQLite 数据库文件路径（默认：用户目录下的 .lingframe/dashboard.db）
     */
    private String path = System.getProperty("user.home") + "/.lingframe/dashboard.db";

    /**
     * 指标数据保留天数
     */
    private int metricsRetentionDays = 7;

    /**
     * 审计日志保留天数
     */
    private int auditRetentionDays = 30;

    /**
     * 指标采集间隔（秒）
     */
    private int metricsCollectIntervalSeconds = 30;

    /**
     * 数据库备份间隔（小时），0 表示不备份
     */
    private int backupIntervalHours = 6;

    /**
     * 备份文件保留数量
     */
    private int backupRetentionCount = 5;
}
