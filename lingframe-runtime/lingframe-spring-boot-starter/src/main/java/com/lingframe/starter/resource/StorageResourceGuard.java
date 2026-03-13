package com.lingframe.starter.resource;

import com.lingframe.core.spi.ResourceGuard;
import com.lingframe.infra.storage.proxy.SqlParseCache;

/**
 * 清理存储层缓存，避免跨灵元残留。
 */
public class StorageResourceGuard implements ResourceGuard {

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        SqlParseCache.evictLing(lingId);
    }

    @Override
    public void detectLeak(String lingId, ClassLoader classLoader) {
        // no-op
    }
}
