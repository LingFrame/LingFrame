package com.lingframe.starter.resource;

import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.infra.storage.proxy.SqlParseCache;

/**
 * 清理存储层缓存，避免跨灵元残留。
 */
public class StorageCacheUnloadHook implements LingUnloadHook {

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        SqlParseCache.evictLing(lingId);
    }
}
