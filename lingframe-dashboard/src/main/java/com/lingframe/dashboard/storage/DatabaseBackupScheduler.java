package com.lingframe.dashboard.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/**
 * SQLite 数据库定时备份
 * 使用文件拷贝方式备份（SQLite 官方推荐的在线备份方式之一）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(name = "dashboardJdbcTemplate")
public class DatabaseBackupScheduler {

    private static final DateTimeFormatter BACKUP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final StorageProperties storageProperties;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 定时备份：按配置的小时间隔执行
     */
    @Scheduled(fixedDelayString = "${lingframe.dashboard.storage.backup-delay-ms:21600000}",
               initialDelayString = "${lingframe.dashboard.storage.backup-delay-ms:21600000}")
    public void backup() {
        if (storageProperties.getBackupIntervalHours() <= 0) {
            return;
        }

        String dbPath = storageProperties.getPath();
        File dbFile = new File(dbPath);
        if (!dbFile.exists()) {
            return;
        }

        try {
            Path backupDir = Paths.get(dbFile.getParent(), "backups");
            Files.createDirectories(backupDir);

            // 备份前执行 WAL checkpoint，确保所有数据写入主库文件
            try {
                jdbcTemplate.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (Exception e) {
                log.warn("WAL checkpoint failed, backup may be incomplete: {}", e.getMessage());
            }

            String backupName = "dashboard_" + LocalDateTime.now().format(BACKUP_FMT) + ".db";
            Path backupPath = backupDir.resolve(backupName);

            Files.copy(dbFile.toPath(), backupPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("Database backup completed: {}", backupPath);

            // 清理过期备份
            cleanupOldBackups(backupDir);

        } catch (Exception e) {
            log.warn("Database backup exception (does not affect operation)", e);
        }
    }

    private void cleanupOldBackups(Path backupDir) {
        int retention = storageProperties.getBackupRetentionCount();
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> backups = files
                    .filter(p -> p.getFileName().toString().startsWith("dashboard_") && p.toString().endsWith(".db"))
                    .sorted((a, b) -> {
                        try {
                            return Long.compare(Files.getLastModifiedTime(b).toMillis(), Files.getLastModifiedTime(a).toMillis());
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());

            for (int i = retention; i < backups.size(); i++) {
                Files.deleteIfExists(backups.get(i));
                log.debug("Delete expired backup: {}", backups.get(i));
            }
        } catch (Exception e) {
            log.warn("Failed to clean up expired backups", e);
        }
    }
}
