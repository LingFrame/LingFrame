package com.lingframe.starter.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.context.ApplicationContext;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * {@link JacksonCacheCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖 null 参数短路、ApplicationContext 异常隔离、正常清理路径不抛异常。
 */
@DisplayName("JacksonCacheCleaner 补充测试")
class JacksonCacheCleanerSupplementTest {

    @Test
    @DisplayName("mainContext 为 null 时应安全返回")
    void shouldReturnWhenMainContextNull() {
        JacksonCacheCleaner cleaner = new JacksonCacheCleaner();
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", null, cl, "cleanup"));
    }

    @Test
    @DisplayName("lingClassLoader 为 null 时应安全返回")
    void shouldReturnWhenClassLoaderNull() {
        JacksonCacheCleaner cleaner = new JacksonCacheCleaner();
        ApplicationContext mainContext = mock(ApplicationContext.class);

        assertDoesNotThrow(() -> cleaner.clear("ling-a", mainContext, null, "cleanup"));
    }

    @Test
    @DisplayName("mainContext.getBeansOfType 抛异常时应被捕获")
    void shouldCatchExceptionWhenGetBeansOfTypeFails() {
        JacksonCacheCleaner cleaner = new JacksonCacheCleaner();
        ApplicationContext mainContext = mock(ApplicationContext.class);
        when(mainContext.getBeansOfType(ObjectMapper.class))
                .thenThrow(new RuntimeException("context not ready"));

        assertDoesNotThrow(() -> cleaner.clear("ling-a", mainContext,
                getClass().getClassLoader(), "cleanup"));
    }

    @Test
    @DisplayName("无 ObjectMapper Bean 时应安全执行不抛异常")
    void shouldHandleEmptyMapperBeans() {
        JacksonCacheCleaner cleaner = new JacksonCacheCleaner();
        ApplicationContext mainContext = mock(ApplicationContext.class);
        when(mainContext.getBeansOfType(ObjectMapper.class))
                .thenReturn(Collections.emptyMap());
        when(mainContext.getBeansOfType(
                MappingJackson2HttpMessageConverter.class))
                .thenReturn(Collections.emptyMap());

        assertDoesNotThrow(() -> cleaner.clear("ling-a", mainContext,
                getClass().getClassLoader(), "cleanup"));
    }

    @Test
    @DisplayName("有真实 ObjectMapper 时应安全清理其内部缓存")
    void shouldSafelyCleanupRealObjectMapper() {
        JacksonCacheCleaner cleaner = new JacksonCacheCleaner();
        ApplicationContext mainContext = mock(ApplicationContext.class);
        ObjectMapper mapper = new ObjectMapper();
        when(mainContext.getBeansOfType(ObjectMapper.class))
                .thenReturn(Collections.singletonMap("objectMapper", mapper));
        when(mainContext.getBeansOfType(
                MappingJackson2HttpMessageConverter.class))
                .thenReturn(Collections.emptyMap());

        // 系统 ClassLoader 不加载灵元类，清理应返回 0 但不抛异常
        assertDoesNotThrow(() -> cleaner.clear("ling-a", mainContext,
                new ClassLoader() {
                }, "cleanup"));
    }
}
