package com.lingframe.infra.storage.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lingframe.api.constant.LingCoreConstants;

import java.util.concurrent.TimeUnit;

/**
 * SQL 解析缓存，用 {@code lingId + "\0" + sql} 作为组合 key 实现按灵元隔离。
 * <p>
 * 采用单个 Caffeine 缓存（并发安全 + LRU + 过期），通过组合 key 保留按灵元隔离语义，
 * 同时支持 evictLing（按前缀清理）与 size（按前缀统计）。
 * <p>
 * 缓存值为完整的 {@link SqlPermissionSupport.SqlPermissionPlan}（含 accessType、
 * capabilities、parseable），命中时可直接复用，避免重复解析。
 */
public final class SqlParseCache {

    private static final int MAX_CACHE_SIZE = 5000;
    private static final long CACHE_EXPIRE_TIME = TimeUnit.MINUTES.toMillis(10);
    private static final String DEFAULT_LING_ID = LingCoreConstants.LINGCORE_LING_ID;

    // 全局缓存：key 为 normalizeLingId(lingId) + "\0" + sql，既保留灵元隔离又共享 LRU 容量
    private static final Cache<String, SqlPermissionSupport.SqlPermissionPlan> CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterAccess(CACHE_EXPIRE_TIME, TimeUnit.MILLISECONDS)
            .build();

    private SqlParseCache() {
    }

    /**
     * 带缓存的 SQL 解析：命中直接返回，未命中则解析后缓存。
     *
     * @param lingId 灵元 ID（可为 null，表示灵核）
     * @param sql SQL 语句
     * @return 权限计划
     */
    public static SqlPermissionSupport.SqlPermissionPlan getOrAnalyze(String lingId, String sql) {
        SqlPermissionSupport.SqlPermissionPlan cached = get(lingId, sql);
        if (cached != null) {
            return cached;
        }
        SqlPermissionSupport.SqlPermissionPlan plan = SqlPermissionSupport.analyze(sql);
        put(lingId, sql, plan);
        return plan;
    }

    public static SqlPermissionSupport.SqlPermissionPlan get(String lingId, String sql) {
        if (sql == null) {
            return null;
        }
        return CACHE.getIfPresent(buildKey(lingId, sql));
    }

    public static void put(String lingId, String sql, SqlPermissionSupport.SqlPermissionPlan plan) {
        if (sql == null || plan == null) {
            return;
        }
        CACHE.put(buildKey(lingId, sql), plan);
    }

    public static void evictLing(String lingId) {
        String prefix = normalizeLingId(lingId) + "\0";
        CACHE.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 清除全部灵元的 SQL 解析缓存，仅供测试 cleanup 使用。
     */
    static void clearAll() {
        CACHE.invalidateAll();
    }

    static int size(String lingId) {
        // Caffeine 的淘汰是 lazy + amortized 的，连续 put 不会立即触发；
        // 在统计前主动 cleanUp，确保返回值反映 maximumSize 上限（仅用于测试断言）。
        CACHE.cleanUp();
        String prefix = normalizeLingId(lingId) + "\0";
        int count = 0;
        for (String key : CACHE.asMap().keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static String buildKey(String lingId, String sql) {
        return normalizeLingId(lingId) + "\0" + sql;
    }

    private static String normalizeLingId(String lingId) {
        return (lingId == null || lingId.isEmpty()) ? DEFAULT_LING_ID : lingId;
    }
}
