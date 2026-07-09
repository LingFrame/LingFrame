package com.lingframe.dashboard.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SQLite 数据库备份调度器单元测试
 * 覆盖：backupIntervalHours≤0 早退 / dbFile 不存在早退 / 正常备份 /
 *      WAL checkpoint 异常隔离 / cleanupOldBackups 保留策略 / 整体异常不传播
 */
class DatabaseBackupSchedulerTest {

    private StorageProperties properties;
    private JdbcTemplate jdbcTemplate;
    private DatabaseBackupScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = mock(StorageProperties.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        scheduler = new DatabaseBackupScheduler(properties, jdbcTemplate);
    }

    @Test
    @DisplayName("backupIntervalHours≤0 应直接返回，不执行备份")
    void shouldSkipWhenBackupIntervalNonPositive(@TempDir Path tempDir) {
        when(properties.getBackupIntervalHours()).thenReturn(0);
        when(properties.getPath()).thenReturn(tempDir.resolve("dashboard.db").toString());

        scheduler.backup();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("dbFile 不存在应直接返回，不执行备份")
    void shouldSkipWhenDbFileMissing(@TempDir Path tempDir) {
        when(properties.getBackupIntervalHours()).thenReturn(6);
        when(properties.getPath()).thenReturn(tempDir.resolve("nonexistent.db").toString());

        scheduler.backup();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    @DisplayName("正常备份应创建 backups 目录、执行 WAL checkpoint、生成备份文件")
    void shouldCreateBackupFile(@TempDir Path tempDir) throws IOException {
        Path dbFile = tempDir.resolve("dashboard.db");
        Files.createFile(dbFile);
        when(properties.getBackupIntervalHours()).thenReturn(6);
        when(properties.getPath()).thenReturn(dbFile.toString());
        when(properties.getBackupRetentionCount()).thenReturn(5);

        scheduler.backup();

        Path backupDir = tempDir.resolve("backups");
        assertTrue(Files.exists(backupDir), "backups 目录应被创建");
        verify(jdbcTemplate).execute("PRAGMA wal_checkpoint(TRUNCATE)");
        try (Stream<Path> files = Files.list(backupDir)) {
            assertEquals(1, files.count(), "应生成 1 个备份文件");
        }
    }

    @Test
    @DisplayName("WAL checkpoint 异常不应阻断备份流程")
    void shouldContinueBackupWhenCheckpointFails(@TempDir Path tempDir) throws IOException {
        Path dbFile = tempDir.resolve("dashboard.db");
        Files.createFile(dbFile);
        doThrow(new RuntimeException("checkpoint failed"))
                .when(jdbcTemplate).execute("PRAGMA wal_checkpoint(TRUNCATE)");
        when(properties.getBackupIntervalHours()).thenReturn(6);
        when(properties.getPath()).thenReturn(dbFile.toString());
        when(properties.getBackupRetentionCount()).thenReturn(5);

        scheduler.backup();

        Path backupDir = tempDir.resolve("backups");
        assertTrue(Files.exists(backupDir), "checkpoint 失败仍应创建备份");
        try (Stream<Path> files = Files.list(backupDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    @DisplayName("cleanupOldBackups 应按 retention 保留最新备份，删除超额旧备份")
    void shouldRetainOnlySpecifiedNumberOfBackups(@TempDir Path tempDir) throws IOException {
        Path dbFile = tempDir.resolve("dashboard.db");
        Files.createFile(dbFile);
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);
        // 预置 7 个旧备份，按递增修改时间（越新越后）
        long base = System.currentTimeMillis() - 70 * 60_000;
        for (int i = 0; i < 7; i++) {
            Path old = backupDir.resolve("dashboard_2026010" + i + "_000000.db");
            Files.createFile(old);
            Files.setLastModifiedTime(old, FileTime.fromMillis(base + i * 60_000));
        }
        when(properties.getBackupIntervalHours()).thenReturn(6);
        when(properties.getPath()).thenReturn(dbFile.toString());
        when(properties.getBackupRetentionCount()).thenReturn(5);

        scheduler.backup();

        // backup 新增 1 个 + 旧 7 个 = 8，retention=5 → 删除 3 个 → 剩 5
        try (Stream<Path> files = Files.list(backupDir)) {
            assertEquals(5, files.count(), "retention=5 应保留 5 个备份文件");
        }
    }

    @Test
    @DisplayName("backup 整体异常不应向上传播（catch 兜底）")
    void shouldNotPropagateBackupException(@TempDir Path tempDir) {
        // path 指向一个目录（不可作为文件 copy），触发 IOException
        when(properties.getBackupIntervalHours()).thenReturn(6);
        when(properties.getPath()).thenReturn(tempDir.toString());
        when(properties.getBackupRetentionCount()).thenReturn(5);

        scheduler.backup();
    }
}
