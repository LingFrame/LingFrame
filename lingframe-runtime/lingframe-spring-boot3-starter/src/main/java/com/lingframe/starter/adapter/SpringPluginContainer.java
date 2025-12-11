package com.lingframe.starter.adapter;

import com.lingframe.api.annotation.LingService;
import com.lingframe.api.context.PluginContext;
import com.lingframe.api.plugin.LingPlugin;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.spi.PluginContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

@Slf4j
public class SpringPluginContainer implements PluginContainer {

    private final SpringApplicationBuilder builder;
    private ConfigurableApplicationContext context;
    private final ClassLoader classLoader;
    // 保存 Context 以便 stop 时使用
    private PluginContext pluginContext;

    public SpringPluginContainer(SpringApplicationBuilder builder, ClassLoader classLoader) {
        this.builder = builder;
        this.classLoader = classLoader;
    }

    @Override
    public void start(PluginContext pluginContext) {
        this.pluginContext = pluginContext;

        // 1. TCCL 劫持
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(classLoader);
        try {
            // 2. 启动 Spring
            this.context = builder.run();

            // 3. 【关键】寻找并触发 LingPlugin 生命周期
            // 尝试从 Spring 容器中获取实现了 LingPlugin 接口的 Bean
            try {
                LingPlugin plugin = this.context.getBean(LingPlugin.class);
                log.info("Triggering onStart for plugin: {}", pluginContext.getPluginId());
                plugin.onStart(pluginContext);

                // 3. 扫描 @LingService 并注册到 Core
                // 等待所有Bean初始化完成后再注册服务
                scheduleServiceRegistration();
            } catch (Exception e) {
                log.warn("No LingPlugin entry point found in plugin: {}", pluginContext.getPluginId());
            }

        } finally {
            t.setContextClassLoader(old);
        }
    }

    /**
     * 延迟服务注册，确保所有Bean都已初始化完成
     */
    private void scheduleServiceRegistration() {
        // 使用Spring的事件机制，在所有Bean初始化完成后注册服务
        if (context instanceof ConfigurableApplicationContext cxt) {
            cxt.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                    log.info("All beans initialized, registering LingServices for plugin: {}", pluginContext.getPluginId());
                    scanAndRegisterLingServices();
                }
            });
        } else {
            // 兜底方案：延迟注册
            Thread delayRegistrationThread = new Thread(() -> {
                try {
                    Thread.sleep(1000); // 等待1秒确保初始化完成
                    scanAndRegisterLingServices();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            delayRegistrationThread.setDaemon(true);
            delayRegistrationThread.start();
        }
        scanAndRegisterLingServices();
    }

    /**
     * 利用 Spring 工具类扫描所有 Bean 中的协议服务
     */
    private void scanAndRegisterLingServices() {
        if (!(pluginContext instanceof CorePluginContext)) {
            log.warn("PluginContext is not instance of CorePluginContext, cannot register services.");
            return;
        }
        PluginManager pluginManager = ((CorePluginContext) pluginContext).getPluginManager();
        String pluginId = pluginContext.getPluginId();

        // 获取容器中所有 Bean 的名称
        String[] beanNames = context.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            try {
                Object bean = context.getBean(beanName);
                // 处理 AOP 代理，获取目标类
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                // 遍历所有方法，查找 @LingService
                ReflectionUtils.doWithMethods(targetClass, method -> {
                    // 使用 AnnotatedElementUtils 支持元注解和代理覆盖
                    LingService lingService = AnnotatedElementUtils.findMergedAnnotation(method, LingService.class);
                    if (lingService != null) {
                        // 组装 FQSID: [PluginID]:[ShortID]
                        String shortId = lingService.id();
                        String fqsid = pluginId + ":" + shortId;

                        // 上报给 Core
                        pluginManager.registerProtocolService(pluginId, fqsid, bean, method);
                    }
                });
            } catch (Exception e) {
                log.warn("Error scanning bean {} for LingServices", beanName, e);
            }
        }
    }

    @Override
    public void stop() {
        if (context != null && context.isActive()) {
            // 1. 【关键】触发 onStop
            try {
                LingPlugin plugin = this.context.getBean(LingPlugin.class);
                log.info("Triggering onStop for plugin: {}", pluginContext.getPluginId());
                plugin.onStop(pluginContext);
            } catch (Exception e) {
                // 忽略，可能没有入口类
            }

            context.close();
        }
        this.context = null;
    }

    @Override
    public boolean isActive() {
        return context != null && context.isActive();
    }

    @Override
    public <T> T getBean(Class<T> type) {
        if (!isActive()) return null;
        try {
            return context.getBean(type);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Object getBean(String beanName) { // <--- 新增此方法实现
        if (!isActive()) return null;
        try {
            return context.getBean(beanName); // 调用 Spring 自身的 getBean(String)
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }
}