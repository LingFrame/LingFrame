package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.AccessType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlParseCacheTest {

    @Test
    void shouldIsolateByLingId() {
        SqlParseCache.put("ling-a", "select 1", AccessType.READ);

        assertNull(SqlParseCache.get("ling-b", "select 1"));
        assertEquals(AccessType.READ, SqlParseCache.get("ling-a", "select 1"));

        SqlParseCache.evictLing("ling-a");
    }

    @Test
    void shouldEvictLingCache() {
        SqlParseCache.put("ling-c", "select 1", AccessType.READ);
        SqlParseCache.evictLing("ling-c");
        assertNull(SqlParseCache.get("ling-c", "select 1"));
    }
}
