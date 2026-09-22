package com.lingframe.infra.cache.spring;

import com.github.benmanes.caffeine.cache.Cache;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.LingCaffeineCacheProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("缓存包装处理器单元测试")
class CacheWrapperProcessorTest {

    private ApplicationContext applicationContext;
    private PermissionService permissionService;
    private ObjectProvider<PermissionService> permissionServiceProvider;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        permissionService = mock(PermissionService.class);
        permissionServiceProvider = mock(ObjectProvider.class);
        when(applicationContext.getBeanProvider(PermissionService.class)).thenReturn(permissionServiceProvider);
    }

    @Test
    @DisplayName("CaffeineWrapperProcessor 测试")
    void testCaffeineWrapperProcessor() {
        CaffeineWrapperProcessor processor = new CaffeineWrapperProcessor();
        processor.setApplicationContext(applicationContext);

        // 1. 正常代理包装
        when(permissionServiceProvider.getIfAvailable()).thenReturn(permissionService);
        Cache<?, ?> cache = mock(Cache.class);
        Object wrapped = processor.postProcessAfterInitialization(cache, "myCache");
        assertTrue(wrapped instanceof LingCaffeineCacheProxy);

        // 2. 非 Cache 对象原样返回
        Object nonCache = new Object();
        assertSame(nonCache, processor.postProcessAfterInitialization(nonCache, "otherBean"));
        assertSame(nonCache, processor.postProcessBeforeInitialization(nonCache, "otherBean"));

        // 3. 防重包装：已经是 LingCaffeineCacheProxy 的不再包装
        assertSame(wrapped, processor.postProcessAfterInitialization(wrapped, "myCache"));

        // 4. 治理未启用（PermissionService bean 未注册）：跳过包装，返回原 bean
        when(permissionServiceProvider.getIfAvailable()).thenReturn(null);
        when(applicationContext.getBeanNamesForType(PermissionService.class, false, false))
                .thenReturn(new String[0]);
        Cache<?, ?> unwrappedCache = mock(Cache.class);
        assertSame(unwrappedCache, processor.postProcessAfterInitialization(unwrappedCache, "unwrappedCache"));

        // 5. 治理启用但 PermissionService 实例未就绪：fail-closed 抛 BeanCreationException，绝不静默裸奔
        Cache<?, ?> nakedCache = mock(Cache.class);
        when(applicationContext.getBeanNamesForType(PermissionService.class, false, false))
                .thenReturn(new String[]{"permissionService"});
        assertThrows(BeanCreationException.class,
                () -> processor.postProcessAfterInitialization(nakedCache, "nakedCache"));
    }

    @Test
    @DisplayName("RedisWrapperProcessor 测试")
    @SuppressWarnings("rawtypes")
    void testRedisWrapperProcessor() {
        RedisWrapperProcessor processor = new RedisWrapperProcessor();
        processor.setApplicationContext(applicationContext);

        // 1. 正常代理包装
        when(permissionServiceProvider.getIfAvailable()).thenReturn(permissionService);
        RedisTemplate<?, ?> template = mock(RedisTemplate.class);
        Object wrapped = processor.postProcessAfterInitialization(template, "myRedisTemplate");
        assertNotNull(wrapped);
        assertTrue(wrapped instanceof RedisTemplate);

        // 2. 非 RedisTemplate 对象原样返回
        Object nonRedis = new Object();
        assertSame(nonRedis, processor.postProcessAfterInitialization(nonRedis, "otherBean"));
        assertSame(nonRedis, processor.postProcessBeforeInitialization(nonRedis, "otherBean"));

        // 3. 防重包装：已经包含 RedisPermissionInterceptor 的代理不再包装
        assertSame(wrapped, processor.postProcessAfterInitialization(wrapped, "myRedisTemplate"));

        // 4. 治理未启用（PermissionService bean 未注册）：跳过包装，返回原 bean
        when(permissionServiceProvider.getIfAvailable()).thenReturn(null);
        when(applicationContext.getBeanNamesForType(PermissionService.class, false, false))
                .thenReturn(new String[0]);
        RedisTemplate<?, ?> unwrappedTemplate = mock(RedisTemplate.class);
        assertSame(unwrappedTemplate, processor.postProcessAfterInitialization(unwrappedTemplate, "unwrappedTemplate"));

        // 5. 治理启用但 PermissionService 实例未就绪：fail-closed 抛 BeanCreationException
        RedisTemplate<?, ?> nakedTemplate = mock(RedisTemplate.class);
        when(applicationContext.getBeanNamesForType(PermissionService.class, false, false))
                .thenReturn(new String[]{"permissionService"});
        assertThrows(BeanCreationException.class,
                () -> processor.postProcessAfterInitialization(nakedTemplate, "nakedTemplate"));
    }

    @Test
    @DisplayName("SpringCacheWrapperProcessor 测试")
    void testSpringCacheWrapperProcessor() {
        SpringCacheWrapperProcessor processor = new SpringCacheWrapperProcessor();
        processor.setApplicationContext(applicationContext);

        // 1. 正常代理包装
        when(permissionServiceProvider.getIfAvailable()).thenReturn(permissionService);
        CacheManager manager = mock(CacheManager.class);
        Object wrapped = processor.postProcessAfterInitialization(manager, "myCacheManager");
        assertTrue(wrapped instanceof LingCacheManagerProxy);

        // 2. 非 CacheManager 对象原样返回
        Object nonManager = new Object();
        assertSame(nonManager, processor.postProcessAfterInitialization(nonManager, "otherBean"));
        assertSame(nonManager, processor.postProcessBeforeInitialization(nonManager, "otherBean"));

        // 3. 防重包装：已经是 LingCacheManagerProxy 的不再包装
        assertSame(wrapped, processor.postProcessAfterInitialization(wrapped, "myCacheManager"));

        // 4. 治理未启用（PermissionService bean 未注册）：跳过包装，返回原 bean
        when(permissionServiceProvider.getIfAvailable()).thenReturn(null);
        when(applicationContext.getBeanNamesForType(PermissionService.class, false, false))
                .thenReturn(new String[0]);
        CacheManager unwrappedManager = mock(CacheManager.class);
        assertSame(unwrappedManager, processor.postProcessAfterInitialization(unwrappedManager, "unwrappedManager"));

        // 5. 治理启用但 PermissionService 实例未就绪：fail-closed 抛 BeanCreationException
        CacheManager nakedManager = mock(CacheManager.class);
        when(applicationContext.getBeanNamesForType(PermissionService.class, false, false))
                .thenReturn(new String[]{"permissionService"});
        assertThrows(BeanCreationException.class,
                () -> processor.postProcessAfterInitialization(nakedManager, "nakedManager"));
    }
}
