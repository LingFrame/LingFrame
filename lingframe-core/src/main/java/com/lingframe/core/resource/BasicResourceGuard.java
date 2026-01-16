package com.lingframe.core.resource;

import com.lingframe.core.spi.ResourceGuard;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.WeakReference;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基础资源守卫实现
 * <p>
 * 提供以下功能：
 * <ul>
 * <li>JDBC 驱动反注册：防止驱动持有 ClassLoader 引用</li>
 * <li>泄漏检测告警：延迟检测 ClassLoader 是否被回收</li>
 * </ul>
 * </p>
 * <p>
 * 可通过继承此类扩展更多清理能力（如线程中断、ThreadLocal 清理等）。
 */
@Slf4j
public class BasicResourceGuard implements ResourceGuard {

    /**
     * 泄漏检测延迟时间（秒）
     */
    private static final int LEAK_DETECTION_DELAY_SECONDS = 5;

    /**
     * 泄漏检测调度器
     * 使用守护线程，不阻止 JVM 退出
     */
    private final ScheduledExecutorService scheduler;

    public BasicResourceGuard() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-leak-detector");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void cleanup(String pluginId, ClassLoader classLoader) {
        log.info("[{}] Starting resource cleanup...", pluginId);

        // 反注册 JDBC 驱动
        int driverCount = deregisterJdbcDrivers(classLoader);
        if (driverCount > 0) {
            log.info("[{}] Deregistered {} JDBC driver(s)", pluginId, driverCount);
        }

        log.info("[{}] Resource cleanup completed", pluginId);
    }

    @Override
    public void detectLeak(String pluginId, ClassLoader classLoader) {
        // 使用 WeakReference 检测 ClassLoader 是否被回收
        WeakReference<ClassLoader> ref = new WeakReference<>(classLoader);

        scheduler.schedule(() -> {
            // 建议 GC（不保证立即执行）
            System.gc();

            // 稍等一下让 GC 完成
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (ref.get() != null) {
                log.warn("⚠️ [{}] ClassLoader 未被回收，可能存在内存泄漏！" +
                        "建议使用分析工具（如 Eclipse MAT）检查引用链。",
                        pluginId);
            } else {
                log.debug("[{}] ClassLoader 已被正常回收", pluginId);
            }
        }, LEAK_DETECTION_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 反注册由指定 ClassLoader 加载的 JDBC 驱动
     * <p>
     * JDBC 驱动通过 DriverManager.registerDriver() 注册后，
     * 会被静态集合持有，导致 ClassLoader 无法被回收。
     * 此方法遍历所有已注册驱动，反注册由插件 ClassLoader 加载的驱动。
     * </p>
     *
     * @param classLoader 插件 ClassLoader
     * @return 反注册的驱动数量
     */
    private int deregisterJdbcDrivers(ClassLoader classLoader) {
        int count = 0;
        Enumeration<Driver> drivers = DriverManager.getDrivers();

        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            ClassLoader driverClassLoader = driver.getClass().getClassLoader();

            // 只反注册由插件 ClassLoader 加载的驱动
            if (driverClassLoader == classLoader) {
                try {
                    DriverManager.deregisterDriver(driver);
                    log.info("Deregistered JDBC driver: {}", driver.getClass().getName());
                    count++;
                } catch (SQLException e) {
                    log.warn("Failed to deregister JDBC driver {}: {}",
                            driver.getClass().getName(), e.getMessage());
                }
            }
        }

        return count;
    }

    /**
     * 关闭泄漏检测调度器
     * <p>
     * 在框架关闭时调用
     * </p>
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
