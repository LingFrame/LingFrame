package com.lingframe.dashboard.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQLite 存储配置属性默认值与读写测试
 */
@DisplayName("SQLite 存储配置属性测试")
class StoragePropertiesTest {

    @Test
    @DisplayName("默认值应与文档一致")
    void shouldHaveCorrectDefaults() {
        StorageProperties p = new StorageProperties();
        assertTrue(p.isEnabled());
        assertTrue(p.getPath().endsWith("/.lingframe/dashboard.db"),
                "默认路径应在用户目录下 .lingframe/dashboard.db");
        assertEquals(7, p.getMetricsRetentionDays());
        assertEquals(30, p.getAuditRetentionDays());
        assertEquals(30, p.getMetricsCollectIntervalSeconds());
        assertEquals(6, p.getBackupIntervalHours());
        assertEquals(5, p.getBackupRetentionCount());
    }

    @Test
    @DisplayName("setter 应正确回写")
    void shouldSetProperties() {
        StorageProperties p = new StorageProperties();
        p.setEnabled(false);
        p.setPath("/tmp/test.db");
        p.setMetricsRetentionDays(14);
        p.setAuditRetentionDays(90);
        p.setMetricsCollectIntervalSeconds(60);
        p.setBackupIntervalHours(12);
        p.setBackupRetentionCount(3);

        assertEquals(false, p.isEnabled());
        assertEquals("/tmp/test.db", p.getPath());
        assertEquals(14, p.getMetricsRetentionDays());
        assertEquals(90, p.getAuditRetentionDays());
        assertEquals(60, p.getMetricsCollectIntervalSeconds());
        assertEquals(12, p.getBackupIntervalHours());
        assertEquals(3, p.getBackupRetentionCount());
    }
}
