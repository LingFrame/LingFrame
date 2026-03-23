package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.AccessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("SqlParseCache 测试")
class SqlParseCacheTest {

    @Nested
    @DisplayName("按灵元隔离")
    class LingIsolationTests {

        @Test
        @DisplayName("不同灵元之间应隔离 SQL 解析缓存")
        void shouldIsolateByLingId() {
            SqlParseCache.put("ling-a", "select 1", AccessType.READ);

            assertNull(SqlParseCache.get("ling-b", "select 1"));
            assertEquals(AccessType.READ, SqlParseCache.get("ling-a", "select 1"));

            SqlParseCache.evictLing("ling-a");
        }
    }

    @Nested
    @DisplayName("缓存清理")
    class CacheEvictionTests {

        @Test
        @DisplayName("按灵元清理后应无法再读取缓存")
        void shouldEvictLingCache() {
            SqlParseCache.put("ling-c", "select 1", AccessType.READ);
            SqlParseCache.evictLing("ling-c");
            assertNull(SqlParseCache.get("ling-c", "select 1"));
        }
    }
}
