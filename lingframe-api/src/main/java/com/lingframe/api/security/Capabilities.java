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

    /**
     * 清空整个缓存（跨灵元共享操作，仅灵核特权放行）
     */
    public static final String CACHE_CLEAR = "cache:clear";

    /**
     * 全量失效缓存（跨灵元共享操作，仅灵核特权放行）
     */
    public static final String CACHE_INVALIDATE = "cache:invalidate";

    /**
     * Caffeine 全量失效（跨灵元共享操作，仅灵核特权放行）
     */
    public static final String CACHE_INVALIDATE_ALL = "cache:invalidateAll";

    /**
     * 暴露原生缓存句柄（灵元拒绝，防绕过命名空间隔离）
     */
    public static final String CACHE_NATIVE_CACHE = "cache:nativeCache";

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
     * 跨灵元调用
     */
    public static final String IPC_INVOKE = "ipc:invoke";

    /**
     * 面向「目标灵元」的动态 IPC 能力前缀。
     * <p>
     * 具体能力键为 {@code IPC_PREFIX + targetLingId}（例如 {@code ipc:user-ling}），
     * 表示"调用目标灵元的服务方法"。通用宣称用 {@link #IPC_INVOKE}，
     * 按目标灵元鉴权用 {@link #ipcCapability(String)} 动态构造，禁止散落字面量拼接。
     */
    public static final String IPC_PREFIX = "ipc:";

    /**
     * 构造针对目标灵元的 IPC 能力键：{@code ipc:<targetLingId>}。
     *
     * @param targetLingId 被调目标灵元 ID（非空）
     * @return 形如 {@code ipc:user-ling} 的能力键
     */
    public static String ipcCapability(String targetLingId) {
        return IPC_PREFIX + targetLingId;
    }

    // ==================== 灵元管理 ====================
    /**
     * 灵元启用权限
     * <p>
     * 允许灵元被启用和执行。所有活跃灵元都应该拥有此权限。
     * </p>
     */
    public static final String LING_ENABLE = "LING_ENABLE";

    private Capabilities() {
        // 防止实例化
    }
}
