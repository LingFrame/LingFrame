package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.core.ResolvableType;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ApplicationListenerCleaner} 补充测试。
 * <p>
 * 该类为包级可见，测试置于同包下直接访问。
 * 重点覆盖 null 上下文短路、多播器异常隔离、监听器引用探测与移除逻辑。
 * 使用自定义 multicaster 类模拟 Spring 内部 defaultRetriever 结构。
 */
@DisplayName("ApplicationListenerCleaner 补充测试")
class ApplicationListenerCleanerSupplementTest {

    /**
     * 持有 lingContext 引用的测试监听器，用于验证 shouldRemove 的反射探测能力。
     */
    static class ContextHoldingListener implements ApplicationListener<ApplicationEvent> {
        @SuppressWarnings("unused")
        private final ConfigurableApplicationContext contextRef;

        ContextHoldingListener(ConfigurableApplicationContext ctx) {
            this.contextRef = ctx;
        }

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
        }
    }

    /**
     * 不持有 lingContext 引用的普通监听器。
     */
    static class InnocentListener implements ApplicationListener<ApplicationEvent> {
        @Override
        public void onApplicationEvent(ApplicationEvent event) {
        }
    }

    /**
     * 自定义 multicaster，直接声明 defaultRetriever 字段，
     * 模拟 Spring AbstractApplicationEventMulticaster 的内部结构。
     */
    static class TestEventMulticaster implements ApplicationEventMulticaster {

        /** 模拟 Spring 的 defaultRetriever，含 applicationListeners Set */
        static class DefaultRetriever {
            final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();
        }

        @SuppressWarnings("unused")
        private final DefaultRetriever defaultRetriever = new DefaultRetriever();

        @Override
        public void addApplicationListener(ApplicationListener<?> listener) {
            defaultRetriever.applicationListeners.add(listener);
        }

        @Override
        public void removeApplicationListener(ApplicationListener<?> listener) {
            defaultRetriever.applicationListeners.remove(listener);
        }

        @Override
        public void addApplicationListenerBean(String listenerBeanName) {
        }

        @Override
        public void removeApplicationListenerBean(String listenerBeanName) {
        }

        @Override
        public void removeApplicationListeners(Predicate<ApplicationListener<?>> predicate) {
        }

        @Override
        public void removeApplicationListenerBeans(Predicate<String> predicate) {
        }

        @Override
        public void removeAllListeners() {
            defaultRetriever.applicationListeners.clear();
        }

        @Override
        public void multicastEvent(ApplicationEvent event) {
        }

        @Override
        public void multicastEvent(ApplicationEvent event, ResolvableType eventType) {
        }
    }

    @Test
    @DisplayName("mainContext 为 null 时应安全跳过")
    void shouldSkipWhenMainContextNull() {
        ApplicationListenerCleaner cleaner = new ApplicationListenerCleaner();
        ConfigurableApplicationContext lingContext = mock(ConfigurableApplicationContext.class);

        assertDoesNotThrow(() -> cleaner.clear("ling-a", null, lingContext));
    }

    @Test
    @DisplayName("lingContext 为 null 时应安全跳过")
    void shouldSkipWhenLingContextNull() {
        ApplicationListenerCleaner cleaner = new ApplicationListenerCleaner();
        ApplicationContext mainContext = mock(ApplicationContext.class);

        assertDoesNotThrow(() -> cleaner.clear("ling-a", mainContext, null));
    }

    @Test
    @DisplayName("mainContext.getBean 抛异常时应被捕获不外抛")
    void shouldCatchExceptionWhenGetBeanFails() {
        ApplicationListenerCleaner cleaner = new ApplicationListenerCleaner();
        ApplicationContext mainContext = mock(ApplicationContext.class);
        when(mainContext.getBean(ApplicationEventMulticaster.class))
                .thenThrow(new RuntimeException("bean not found"));

        assertDoesNotThrow(() -> cleaner.clear("ling-a", mainContext,
                mock(ConfigurableApplicationContext.class)));
    }

    @Test
    @DisplayName("应移除持有 lingContext 引用的监听器，保留无关监听器")
    void shouldRemoveListenerHoldingLingContext() {
        ConfigurableApplicationContext lingContext = mock(ConfigurableApplicationContext.class);
        TestEventMulticaster multicaster = new TestEventMulticaster();

        // 添加一个持有 lingContext 引用的监听器（应被移除）
        ContextHoldingListener targetListener = new ContextHoldingListener(lingContext);
        multicaster.addApplicationListener(targetListener);
        // 添加一个不持有引用的监听器（应保留）
        InnocentListener innocentListener = new InnocentListener();
        multicaster.addApplicationListener(innocentListener);

        ApplicationContext mainContext = mock(ApplicationContext.class);
        when(mainContext.getBean(ApplicationEventMulticaster.class)).thenReturn(multicaster);

        ApplicationListenerCleaner cleaner = new ApplicationListenerCleaner();
        cleaner.clear("ling-a", mainContext, lingContext);

        // targetListener 应已被移除
        assertFalse(multicaster.defaultRetriever.applicationListeners.contains(targetListener),
                "持有 lingContext 引用的监听器应已被移除");
        // innocentListener 应保留
        assertTrue(multicaster.defaultRetriever.applicationListeners.contains(innocentListener),
                "无关监听器应保留");
    }

    @Test
    @DisplayName("无匹配监听器时不应移除任何内容")
    void shouldNotRemoveWhenNoMatch() {
        ConfigurableApplicationContext lingContext = mock(ConfigurableApplicationContext.class);
        TestEventMulticaster multicaster = new TestEventMulticaster();

        InnocentListener listener = new InnocentListener();
        multicaster.addApplicationListener(listener);

        ApplicationContext mainContext = mock(ApplicationContext.class);
        when(mainContext.getBean(ApplicationEventMulticaster.class)).thenReturn(multicaster);

        ApplicationListenerCleaner cleaner = new ApplicationListenerCleaner();
        cleaner.clear("ling-a", mainContext, lingContext);

        // listener 应仍保留
        assertTrue(multicaster.defaultRetriever.applicationListeners.contains(listener),
                "listener 应保留在 multicaster 中");
    }
}
