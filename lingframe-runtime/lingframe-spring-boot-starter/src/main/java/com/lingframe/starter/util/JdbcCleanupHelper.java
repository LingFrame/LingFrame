package com.lingframe.starter.util;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;

public class JdbcCleanupHelper {

    /**
     * 该方法将被灵核通过反射调用（由于此方法和类在 LingClassLoader 内运行，
     * 因此能够越过 DriverManager 的 @CallerSensitive 检查找到所有属于子容器的 JDBC 驱动），
     * 从而能在 JDK 17 不添加 add-opens 绕过反射封装的情况下清理灵核驱动泄漏。
     */
    public static int deregisterAll() {
        int count = 0;
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver d = drivers.nextElement();
            try {
                DriverManager.deregisterDriver(d);
                count++;
            } catch (Exception e) {
                // ignore
            }
        }
        return count;
    }
}
