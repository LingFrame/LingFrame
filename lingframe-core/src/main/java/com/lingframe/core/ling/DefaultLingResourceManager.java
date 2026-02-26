package com.lingframe.core.ling;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultLingResourceManager implements LingResourceManager {

    @Override
    public void cleanupCaches(String pluginId, ClassLoader classLoader) {
        log.info("Cleaning up caches for plugin: {}", pluginId);
        // V0.3.0 预留汇聚点：将各处分散的强引用清理（如 JSON、EL 表达式缓存等）迁移至此
    }

    @Override
    public void closeResources(String pluginId) {
        log.info("Closing resources for plugin: {}", pluginId);
        // V0.3.0 预留汇聚点：释放数据库连接、归还线程池预算等
    }
}
