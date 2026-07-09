package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcDriverUnloadHook} 的补充测试。
 * <p>
 * 已有 {@link JvmUnloadHookTest.JdbcDriverHookTest} 覆盖基础安全校验路径，
 * 此处补充：通过 URLClassLoader 隔离加载的 TestDriver 注册/反注册全链路、
 * 空/null lingId、幂等调用等分支。
 */
@DisplayName("JdbcDriverUnloadHook 补充测试")
class JdbcDriverUnloadHookSupplementTest {

    private final JdbcDriverUnloadHook hook = new JdbcDriverUnloadHook();

    /**
     * 通过 parent=null 的 URLClassLoader 加载 TestDriver，使其 getClassLoader()
     * 等于该 URLClassLoader，从而被卸载钩子的 ClassLoader 匹配规则命中。
     */
    private ClassLoader createIsolatedClassLoader() throws Exception {
        java.net.URL testClassesUrl = JdbcDriverUnloadHookSupplementTest.class
                .getProtectionDomain().getCodeSource().getLocation();
        return new java.net.URLClassLoader(
                new java.net.URL[]{testClassesUrl}, null);
    }

    @Test
    @DisplayName("cleanup 扩展/平台 ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipPlatformClassLoader() {
        ClassLoader platform = ClassLoader.getSystemClassLoader().getParent();
        assertDoesNotThrow(() -> hook.cleanup("ling-jdbc", platform));
    }

    @Test
    @DisplayName("cleanup 自定义 ClassLoader 无匹配 Driver 时为空操作")
    void shouldNoopWhenNoMatchingDrivers() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-jdbc", customCL));
    }

    @Test
    @DisplayName("cleanup 空 lingId 不报错")
    void shouldCleanupWithEmptyLingId() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("", customCL));
    }

    @Test
    @DisplayName("cleanup null lingId 不报错")
    void shouldCleanupWithNullLingId() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup(null, customCL));
    }

    @Test
    @DisplayName("cleanup 同一 ClassLoader 多次调用不报错（幂等）")
    void shouldCleanupMultipleTimes() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> {
            hook.cleanup("ling-jdbc", customCL);
            hook.cleanup("ling-jdbc", customCL);
        });
    }

    @Test
    @DisplayName("cleanup 应反注册由目标 ClassLoader 加载的 JDBC Driver")
    void shouldDeregisterDriverLoadedByTargetClassLoader() throws Exception {
        ClassLoader isolatedCL = createIsolatedClassLoader();
        try {
            // 通过隔离 CL 加载 TestDriver 并实例化
            Class<?> driverClass = Class.forName(
                    "com.lingframe.core.resource.TestDriver", true, isolatedCL);
            assertSame(isolatedCL, driverClass.getClassLoader());
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();

            // 注册到全局 DriverManager
            DriverManager.registerDriver(driver);
            try {
                // 确认已注册
                assertTrue(isDriverRegistered(driver),
                        "Driver 应已注册到 DriverManager");

                // 执行清理
                assertDoesNotThrow(() -> hook.cleanup("ling-jdbc", isolatedCL));

                // 反射路径会直接从内部列表移除；公开 API 路径受 caller 检查限制可能失败
                // 这里断言 Driver 已从注册表中移除（反射路径成功的情况下）
                assertFalse(isDriverRegistered(driver),
                        "Driver 应已被反注册");
            } finally {
                // 兜底清理：确保不污染后续测试
                // 注意：若 hook.cleanup 已通过反射移除 driver，再次 deregister 会因 driver 不在列表中
                // 触发权限检查抛 SecurityException（非 SQLException），故需捕获 Exception
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (Exception ignored) {
                    // 已被清理或权限不足则忽略
                }
            }
        } finally {
            if (isolatedCL instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) isolatedCL).close();
                } catch (Exception ignored) {
                    // 关闭失败不影响测试结论
                }
            }
        }
    }

    /**
     * 判断指定 Driver 是否仍在 DriverManager 注册表中。
     * <p>
     * 注意：{@link DriverManager#getDrivers()} 会按 caller ClassLoader 过滤，
     * 隔离 CL 加载的 driver 对测试类不可见。因此这里通过反射直接读取
     * {@code registeredDrivers} 字段（与 {@link JdbcDriverUnloadHook} 的反射路径一致）。
     */
    @SuppressWarnings("unchecked")
    private boolean isDriverRegistered(Driver target) throws Exception {
        if (JvmCleanupSupport.DRIVER_MANAGER_FIELD == null) {
            // 反射字段不可用时退回 getDrivers（隔离 CL 的 driver 可能不可见）
            Enumeration<Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                if (drivers.nextElement() == target) {
                    return true;
                }
            }
            return false;
        }
        List<Object> registered = (List<Object>) JvmCleanupSupport.DRIVER_MANAGER_FIELD.get(null);
        Field driverField = null;
        for (Object info : registered) {
            if (driverField == null) {
                driverField = info.getClass().getDeclaredField("driver");
                driverField.setAccessible(true);
            }
            Driver d = (Driver) driverField.get(info);
            if (d == target) {
                return true;
            }
        }
        return false;
    }
}
