package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JDBC 驱动注销卸载钩子。
 * <p>
 * 清理由目标 ClassLoader 加载的 JDBC 驱动，防止驱动泄漏。
 * 三级策略：灵元 Helper → 反射绕过 Caller 检查 → 公开 API 兜底。
 * <p>
 * 项目方法命名统一 {@code unregister*}；调用 JDK 时仍使用官方
 * {@link DriverManager#deregisterDriver(Driver)}。
 */
@Slf4j
public class JdbcDriverUnloadHook implements LingUnloadHook {

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        if (!JvmCleanupSupport.isSafeToCleanup(lingId, classLoader)) {
            return;
        }
        int count = unregisterJdbcDrivers(lingId, classLoader);
        if (count > 0) {
            log.info("[{}] Unregistered {} JDBC driver(s)", lingId, count);
        } else {
            log.debug("[{}] No JDBC drivers found to unregister", lingId);
        }
    }

    private int unregisterJdbcDrivers(String lingId, ClassLoader classLoader) {
        // 1. 优先尝试让灵元自己清理（通过 LingClassLoader 加载的 Helper 避开 CallerSensitive）
        try {
            Class<?> helperClass = classLoader.loadClass("com.lingframe.starter.util.JdbcCleanupHelper");
            // 只有当 Helper 真的是由该 ClassLoader 加载的，才调用，否则跳过
            if (helperClass.getClassLoader() == classLoader) {
                Method cleanupMethod = helperClass.getMethod("unregisterAll");
                int count = (Integer) cleanupMethod.invoke(null);
                if (count > 0) {
                    log.info("[{}] Unregistered {} JDBC driver(s) via JdbcCleanupHelper", lingId, count);
                    return count;
                }
            }
        } catch (Throwable e) {
            log.debug("[{}] JdbcCleanupHelper not found or failed: {}", lingId, e.getMessage());
        }

        // 2. 反射清理（绕过 Caller 检查，需要 add-opens java.sql/java.sql=ALL-UNNAMED）
        if (JvmCleanupSupport.DRIVER_MANAGER_FIELD != null) {
            int count = unregisterJdbcDriversReflection(lingId, classLoader);
            if (count > 0) {
                return count;
            }
        }

        // 3. 兜底：公开 API（调用方是灵核 ClassLoader 时通常找不到灵元 Driver，仅非隔离场景）
        return unregisterJdbcDriversPublicApi(lingId, classLoader);
    }

    /**
     * Java 9+ 公开 API
     */
    private int unregisterJdbcDriversPublicApi(String lingId, ClassLoader classLoader) {
        int count = 0;
        List<Driver> toUnregister = new ArrayList<>();

        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver d = drivers.nextElement();
            if (d.getClass().getClassLoader() == classLoader) {
                toUnregister.add(d);
            }
        }

        for (Driver d : toUnregister) {
            try {
                DriverManager.deregisterDriver(d);
                count++;
                log.info("[{}] Unregistered JDBC driver: {}", lingId, d.getClass().getName());
            } catch (Exception e) {
                log.warn("[{}] Failed to unregister {}: {}", lingId, d.getClass().getName(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * Java 8 反射方式（绕过 caller ClassLoader 检查）
     */
    @SuppressWarnings("unchecked")
    private int unregisterJdbcDriversReflection(String lingId, ClassLoader classLoader) {
        int count = 0;
        try {
            CopyOnWriteArrayList<Object> drivers =
                    (CopyOnWriteArrayList<Object>) JvmCleanupSupport.DRIVER_MANAGER_FIELD.get(null);
            if (drivers == null) {
                return 0;
            }

            List<Object> toRemove = new ArrayList<>();
            Field driverField = null;

            for (Object info : drivers) {
                if (driverField == null) {
                    driverField = info.getClass().getDeclaredField("driver");
                    driverField.setAccessible(true);
                }
                Driver d = (Driver) driverField.get(info);
                if (d != null && d.getClass().getClassLoader() == classLoader) {
                    toRemove.add(info);
                }
            }

            for (Object info : toRemove) {
                Driver d = (Driver) driverField.get(info);
                try {
                    DriverManager.deregisterDriver(d);
                } catch (Exception e) {
                    drivers.remove(info);
                }
                count++;
                log.info("[{}] Unregistered JDBC driver: {}", lingId, d.getClass().getName());
            }
        } catch (Exception e) {
            log.debug("[{}] Driver unregister (reflection) failed: {}", lingId, e.getMessage());
            return unregisterJdbcDriversPublicApi(lingId, classLoader);
        }
        return count;
    }
}
