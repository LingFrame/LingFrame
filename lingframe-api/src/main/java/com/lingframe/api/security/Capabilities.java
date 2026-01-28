package com.lingframe.api.security;

/**
 * 统一权限能力常量
 * <p>
 * 所有前后端权限检查都应使用此类中定义的常量，确保一致性。
 * </p>
 */
public final class Capabilities {

    // ==================== 数据库 ====================
    /**
     * SQL 数据库访问
     * <p>
     * AccessType.READ = SELECT
     * </p>
     * <p>
     * AccessType.WRITE = INSERT/UPDATE/DELETE
     * </p>
     */
    public static final String STORAGE_SQL = "storage:sql";

    // ==================== 缓存 ====================
    /**
     * 本地缓存（Spring Cache / Caffeine）
     */
    public static final String CACHE_LOCAL = "cache:local";

    /**
     * Redis 缓存
     */
    public static final String CACHE_REDIS = "cache:redis";

    // ==================== 网络 ====================
    /**
     * HTTP 出站请求
     */
    public static final String NETWORK_HTTP = "network:http";

    /**
     * RPC 调用
     */
    public static final String NETWORK_RPC = "network:rpc";

    // ==================== 文件 ====================
    /**
     * 文件读取
     */
    public static final String FILE_READ = "file:read";

    /**
     * 文件写入
     */
    public static final String FILE_WRITE = "file:write";

    // ==================== IPC ====================
    /**
     * 跨插件调用
     */
    public static final String IPC_INVOKE = "ipc:invoke";

    // ==================== 插件管理 ====================
    /**
     * 插件启用权限
     * <p>
     * 允许插件被启用和执行。所有活跃插件都应该拥有此权限。
     * </p>
     */
    public static final String PLUGIN_ENABLE = "PLUGIN_ENABLE";

    private Capabilities() {
        // 防止实例化
    }
}
