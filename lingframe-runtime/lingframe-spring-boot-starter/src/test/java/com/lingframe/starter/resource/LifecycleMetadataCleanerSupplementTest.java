package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

/**
 * {@link LifecycleMetadataCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖非 DefaultListableBeanFactory 短路、null ClassLoader 容错、
 * 正常 BeanFactory 清理路径不抛异常。
 */
@DisplayName("LifecycleMetadataCleaner 补充测试")
class LifecycleMetadataCleanerSupplementTest {

    @Test
    @DisplayName("beanFactory 非 DefaultListableBeanFactory 时应安全跳过")
    void shouldSkipWhenNotDefaultListableBeanFactory() {
        LifecycleMetadataCleaner cleaner = new LifecycleMetadataCleaner();
        ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", beanFactory, cl));
    }

    @Test
    @DisplayName("DefaultListableBeanFactory 下应安全执行清理")
    void shouldSafelyClearWithDefaultListableBeanFactory() {
        LifecycleMetadataCleaner cleaner = new LifecycleMetadataCleaner();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", beanFactory, cl));
    }

    @Test
    @DisplayName("null ClassLoader 下应安全执行")
    void shouldHandleNullClassLoader() {
        LifecycleMetadataCleaner cleaner = new LifecycleMetadataCleaner();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", beanFactory, null));
    }

    @Test
    @DisplayName("null lingId 下应安全执行")
    void shouldHandleNullLingId() {
        LifecycleMetadataCleaner cleaner = new LifecycleMetadataCleaner();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ClassLoader cl = getClass().getClassLoader();

        assertDoesNotThrow(() -> cleaner.clear(null, beanFactory, cl));
    }
}
