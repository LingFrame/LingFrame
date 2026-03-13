package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.AccessType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SQL parse cache scoped by lingId to avoid cross-ling interference.
 */
public final class SqlParseCache {

    private static final int MAX_CACHE_SIZE = 1000;
    private static final long CACHE_EXPIRE_TIME = TimeUnit.MINUTES.toMillis(10);
    private static final String DEFAULT_LING_ID = "LINGCORE";

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, SqlParseResult>> CACHE =
            new ConcurrentHashMap<>();

    private SqlParseCache() {
    }

    public static AccessType get(String lingId, String sql) {
        if (sql == null) {
            return null;
        }
        ConcurrentHashMap<String, SqlParseResult> lingCache = CACHE.get(normalizeLingId(lingId));
        if (lingCache == null) {
            return null;
        }
        SqlParseResult cached = lingCache.get(sql);
        if (cached == null) {
            return null;
        }
        if (cached.isExpired()) {
            lingCache.remove(sql);
            return null;
        }
        return cached.accessType;
    }

    public static void put(String lingId, String sql, AccessType accessType) {
        if (sql == null || accessType == null) {
            return;
        }
        String key = normalizeLingId(lingId);
        ConcurrentHashMap<String, SqlParseResult> lingCache =
                CACHE.computeIfAbsent(key, id -> new ConcurrentHashMap<>());

        if (lingCache.size() >= MAX_CACHE_SIZE) {
            lingCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }
        if (lingCache.size() < MAX_CACHE_SIZE) {
            lingCache.put(sql, new SqlParseResult(accessType));
        }
    }

    public static void evictLing(String lingId) {
        CACHE.remove(normalizeLingId(lingId));
    }

    static int size(String lingId) {
        ConcurrentHashMap<String, SqlParseResult> lingCache = CACHE.get(normalizeLingId(lingId));
        return lingCache == null ? 0 : lingCache.size();
    }

    private static String normalizeLingId(String lingId) {
        return (lingId == null || lingId.isEmpty()) ? DEFAULT_LING_ID : lingId;
    }

    private static class SqlParseResult {
        final AccessType accessType;
        final long timestamp;

        SqlParseResult(AccessType accessType) {
            this.accessType = accessType;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRE_TIME;
        }
    }
}
