package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

/**
 * RMI Target 卸载钩子。
 * <p>
 * 清理 sun.rmi.transport.ObjectTable 中关联目标 ClassLoader 的 Target 条目。
 * 灵元若使用 RMI（如 JMX 远程导出、远程服务发布），卸载时必须清理 RMI Target，
 * 否则 ObjectTable 的静态 Map 会持有灵元 ClassLoader 引用。
 * <p>
 * 兼容 JDK 8/11/17/21，字段名和结构在不同版本可能有差异。
 */
@Slf4j
public class RmiTargetUnloadHook implements LingUnloadHook {

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        if (!JvmCleanupSupport.isSafeToCleanup(lingId, classLoader)) {
            return;
        }
        int removed = clearObjectTable(lingId, classLoader, "implTable");
        removed += clearObjectTable(lingId, classLoader, "objTable");
        if (removed > 0) {
            log.info("[{}] Removed {} RMI Target(s)", lingId, removed);
        }
    }

    /**
     * 清理 ObjectTable 的指定表（implTable 或 objTable）。
     */
    private int clearObjectTable(String lingId, ClassLoader classLoader, String tableName) {
        try {
            Class<?> objectTableClass = Class.forName("sun.rmi.transport.ObjectTable");
            Field tableField = objectTableClass.getDeclaredField(tableName);
            tableField.setAccessible(true);
            Map<?, ?> table = (Map<?, ?>) tableField.get(null);
            if (table == null || table.isEmpty()) {
                return 0;
            }

            int removed = 0;
            // 使用迭代器安全移除
            for (Object key : new ArrayList<>(table.keySet())) {
                Object target = table.get(key);
                if (target == null) continue;

                ClassLoader targetCl = extractTargetClassLoader(target);
                if (targetCl == classLoader || isLoadedBy(targetCl, classLoader)) {
                    try {
                        table.remove(key);
                        log.info("[{}] Removed RMI Target from {}: {}",
                                lingId, tableName, target.getClass().getName());
                        removed++;
                    } catch (Exception e) {
                        log.debug("[{}] Failed to remove RMI Target: {}", lingId, e.getMessage());
                    }
                }
            }
            return removed;
        } catch (ClassNotFoundException e) {
            // 非 Oracle/OpenJDK JVM（如 IBM J9），没有 sun.rmi 类
            log.debug("[{}] sun.rmi.transport.ObjectTable not available, skip RMI cleanup", lingId);
            return 0;
        } catch (NoSuchFieldException e) {
            // JDK 版本不同，字段名可能变化
            log.debug("[{}] ObjectTable.{} field not found, JDK version compatibility issue",
                    lingId, tableName);
            return 0;
        } catch (Exception e) {
            log.debug("[{}] RMI Target cleanup failed for {}: {}", lingId, tableName, e.getMessage());
            return 0;
        }
    }

    /**
     * 反射获取 Target 对象的 ccl 字段（contextClassLoader）。
     * sun.rmi.transport.Target 的 ccl 字段持有 ClassLoader 引用。
     */
    private ClassLoader extractTargetClassLoader(Object target) {
        try {
            Field cclField = target.getClass().getDeclaredField("ccl");
            cclField.setAccessible(true);
            return (ClassLoader) cclField.get(target);
        } catch (NoSuchFieldException e) {
            // 尝试其他字段名
            return extractClassLoaderViaFields(target);
        } catch (Exception e) {
            return null;
        }
    }

    /** 遍历 Target 的字段查找 ClassLoader */
    private ClassLoader extractClassLoaderViaFields(Object target) {
        try {
            for (Field f : target.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object value = f.get(target);
                if (value instanceof ClassLoader) {
                    return (ClassLoader) value;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract ClassLoader from Target: {}", e.getMessage());
        }
        return null;
    }

    /** 判断 ClassLoader cl 是否由 target 加载（或就是 target） */
    private boolean isLoadedBy(ClassLoader cl, ClassLoader target) {
        while (cl != null) {
            if (cl == target) return true;
            cl = cl.getParent();
        }
        return false;
    }
}
