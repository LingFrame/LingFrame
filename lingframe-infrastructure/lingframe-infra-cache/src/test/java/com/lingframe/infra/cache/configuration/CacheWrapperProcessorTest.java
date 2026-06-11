package com.lingframe.infra.cache.configuration;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.LingCaffeineCacheProxy;
import com.lingframe.infra.cache.proxy.LingCacheManagerProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("缓存包装处理器单元测试")
class CacheWrapperProcessorTest {

    private ApplicationContext applicationContext;
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        permissionService = mock(PermissionService.class);
    }

    @Test
    @DisplayName("CaffeineWrapperProcessor 测试")
    void testCaffeineWrapperProcessor() {
        CaffeineWrapperProcessor processor = new CaffeineWrapperProcessor();
        processor.setApplicationContext(applicationContext);

        // 1. 正常代理包装
        when(applicationContext.getBean(PermissionService.class)).thenReturn(permissionService);
        Cache<?, ?> cache = mock(Cache.class);
        Object wrapped = processor.postProcessAfterInitialization(cache, "myCache");
        assertTrue(wrapped instanceof LingCaffeineCacheProxy);

        // 2. 非 Cache 对象原样返回
        Object nonCache = new Object();
        assertSame(nonCache, processor.postProcessAfterInitialization(nonCache, "otherBean"));
        assertSame(nonCache, processor.postProcessBeforeInitialization(nonCache, "otherBean"));

        // 3. 异常回退（不设置 Context 导致其为 null，或 Context 查找 Bean 异常）
        CaffeineWrapperProcessor badProcessor1 = new CaffeineWrapperProcessor();
        assertSame(cache, badProcessor1.postProcessAfterInitialization(cache, "myCache"));

        CaffeineWrapperProcessor badProcessor2 = new CaffeineWrapperProcessor();
        badProcessor2.setApplicationContext(applicationContext);
        when(applicationContext.getBean(PermissionService.class)).thenThrow(new RuntimeException("mock error"));
        assertSame(cache, badProcessor2.postProcessAfterInitialization(cache, "myCache"));
    }

    @Test
    @DisplayName("RedisWrapperProcessor 测试")
    @SuppressWarnings("rawtypes")
    void testRedisWrapperProcessor() {
        RedisWrapperProcessor processor = new RedisWrapperProcessor();
        processor.setApplicationContext(applicationContext);

        // 1. 正常代理包装
        when(applicationContext.getBean(PermissionService.class)).thenReturn(permissionService);
        RedisTemplate<?, ?> template = mock(RedisTemplate.class);
        Object wrapped = processor.postProcessAfterInitialization(template, "myRedisTemplate");
        assertNotNull(wrapped);
        assertTrue(wrapped instanceof RedisTemplate);

        // 2. 非 RedisTemplate 对象原样返回
        Object nonRedis = new Object();
        assertSame(nonRedis, processor.postProcessAfterInitialization(nonRedis, "otherBean"));
        assertSame(nonRedis, processor.postProcessBeforeInitialization(nonRedis, "otherBean"));

        // 3. 异常回退（不设置 Context 或发生查找异常）
        RedisWrapperProcessor badProcessor1 = new RedisWrapperProcessor();
        assertSame(template, badProcessor1.postProcessAfterInitialization(template, "myRedisTemplate"));

        RedisWrapperProcessor badProcessor2 = new RedisWrapperProcessor();
        badProcessor2.setApplicationContext(applicationContext);
        when(applicationContext.getBean(PermissionService.class)).thenThrow(new RuntimeException("mock error"));
        assertSame(template, badProcessor2.postProcessAfterInitialization(template, "myRedisTemplate"));
    }

    @Test
    @DisplayName("SpringCacheWrapperProcessor 测试")
    void testSpringCacheWrapperProcessor() {
        SpringCacheWrapperProcessor processor = new SpringCacheWrapperProcessor();
        processor.setApplicationContext(applicationContext);

        // 1. 正常代理包装
        when(applicationContext.getBean(PermissionService.class)).thenReturn(permissionService);
        CacheManager manager = mock(CacheManager.class);
        Object wrapped = processor.postProcessAfterInitialization(manager, "myCacheManager");
        assertTrue(wrapped instanceof LingCacheManagerProxy);

        // 2. 非 CacheManager 对象原样返回
        Object nonManager = new Object();
        assertSame(nonManager, processor.postProcessAfterInitialization(nonManager, "otherBean"));
        assertSame(nonManager, processor.postProcessBeforeInitialization(nonManager, "otherBean"));

        // 3. 异常回退（发生异常）
        SpringCacheWrapperProcessor badProcessor = new SpringCacheWrapperProcessor();
        badProcessor.setApplicationContext(applicationContext);
        when(applicationContext.getBean(PermissionService.class)).thenThrow(new RuntimeException("mock error"));
        assertSame(manager, badProcessor.postProcessAfterInitialization(manager, "myCacheManager"));
    }
}
