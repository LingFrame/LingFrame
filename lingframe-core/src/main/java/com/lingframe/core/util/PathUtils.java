package com.lingframe.core.util;

import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径解析工具类。
 * 用于将绝对路径或相对路径解析为真实存在的绝对路径。
 */
@Slf4j
public class PathUtils {

    /**
     * 解析相对或绝对路径，始终返回规范化的绝对路径。
     * 采用“多基准目录 + 向上祖先链搜索（Multi-Base Directory Ancestor Search）”策略，
     * 自动从当前工作目录及 Classpath 中提取所有物理路径作为搜索起点，以彻底解决不同启动目录下相对路径失效的问题。
     *
     * @param path        待解析的路径
     * @param lingHomeDir 灵珑的主目录
     * @return 规范化的绝对路径，如果不存在则 fallback 默认值
     */
    public static File resolvePath(String path, File lingHomeDir) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }

        File file = new File(path);

        // 1. 如果是绝对路径，直接返回规范化文件
        if (file.isAbsolute()) {
            return getTypeSafeFile(file);
        }

        // 2. 相对路径：从当前工作目录及所有类路径中提取可能的基础目录，逐一向上祖先链遍历拼合匹配
        try {
            List<File> baseDirs = getPotentialBaseDirs();
            for (File base : baseDirs) {
                File current = base;
                while (current != null) {
                    File candidate = new File(current, path);
                    if (candidate.exists()) {
                        log.info("[PathUtils] Resolved relative path '{}' to existing file '{}' via base directory '{}'",
                                path, candidate.getAbsolutePath(), current.getAbsolutePath());
                        return getTypeSafeFile(candidate);
                    }
                    current = current.getParentFile();
                }
            }
        } catch (Exception e) {
            log.warn("[PathUtils] Error traversing ancestor directory chain for path: {}", path, e);
        }

        // 3. 如果在所有基准目录及祖先链中均未找到，则尝试相对于 lingHome 解析
        if (lingHomeDir != null) {
            File lingFile = new File(lingHomeDir, path);
            if (lingFile.exists()) {
                return getTypeSafeFile(lingFile);
            }
        }

        // 4. 退化方案：依然返回相对于当前工作目录的绝对路径，便于上层提示更准确的错误日志
        return getTypeSafeFile(new File(path));
    }

    /**
     * 从当前工作目录及系统类路径 (classpath) 中解析可能存在的本地目录，作为路径查找的搜索基准。
     */
    private static List<File> getPotentialBaseDirs() {
        List<File> baseDirs = new ArrayList<>();

        // 1. 获取当前工作目录 (Cwd)
        try {
            baseDirs.add(new File(".").getCanonicalFile());
        } catch (Exception ignored) {}

        // 2. 获取 JVM 系统类路径中的物理目录和 Jar 包物理父目录
        String classpath = System.getProperty("java.class.path");
        if (classpath != null && !classpath.isEmpty()) {
            String separator = System.getProperty("path.separator", ";");
            String[] paths = classpath.split(separator);
            for (String p : paths) {
                try {
                    File file = new File(p);
                    if (file.exists()) {
                        File dir = file.isDirectory() ? file : file.getParentFile();
                        if (dir != null) {
                            File canonical = dir.getCanonicalFile();
                            if (!baseDirs.contains(canonical)) {
                                baseDirs.add(canonical);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        return baseDirs;
    }

    private static File getTypeSafeFile(File file) {
        try {
            return file.getCanonicalFile();
        } catch (Exception e) {
            return file.getAbsoluteFile();
        }
    }
}
