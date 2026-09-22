package com.lingframe.starter.adapter;

import com.lingframe.api.context.LingContext;
import com.lingframe.core.spi.LingContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

/**
 * 灵核容器适配器。
 * <p>
 * 把灵核 {@link ApplicationContext} 适配成 {@link LingContainer},使灵核可以作为
 * CORE provider(lingId={@code lingcore-app})参与 Pipeline 路由,让灵元通过
 * @LingReference 反向调用灵核 Bean。
 * <p>
 * 与灵元 {@code SpringLingContainer} 的差异:
 * <ul>
 *   <li>{@link #start(LingContext)} 空实现:灵核 ApplicationContext 由 Spring Boot 主流程管理</li>
 *   <li>{@link #stop()} 仅置标记位:进程级生命周期,不真实关闭 ApplicationContext</li>
 *   <li>{@link #getClassLoader()} 返回灵核 ClassLoader,与灵元 Child-First LingClassLoader 区分</li>
 * </ul>
 */
@Slf4j
public class LingCoreContainerAdapter implements LingContainer {

    private final ApplicationContext applicationContext;
    private final ClassLoader coreClassLoader;
    private volatile boolean active = true;

    public LingCoreContainerAdapter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        // 灵核 ClassLoader:优先用 ApplicationContext 的,兜底当前线程 TCCL
        ClassLoader cl = applicationContext.getClassLoader();
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
        }
        this.coreClassLoader = cl;
    }

    @Override
    public void start(LingContext context) {
        // 灵核 ApplicationContext 由 Spring Boot 主流程管理,此处不重复启动
        log.debug("Ling core container start invoked, but ApplicationContext is managed by Spring Boot main flow");
    }

    @Override
    public void stop() {
        // 灵核是进程级生命周期,不在此真实关闭 ApplicationContext
        // 仅置标记位,JVM Shutdown Hook 会处理 ApplicationContext 关闭
        active = false;
        log.debug("Ling core container stop invoked, marking inactive (process-level lifecycle)");
    }

    @Override
    public boolean isActive() {
        return active && applicationContext != null;
    }

    @Override
    public <T> T getBean(Class<T> type) {
        if (!isActive()) {
            return null;
        }
        return applicationContext.getBean(type);
    }

    @Override
    public Object getBean(String beanName) {
        if (!isActive()) {
            return null;
        }
        return applicationContext.getBean(beanName);
    }

    @Override
    public String[] getBeanNames() {
        if (!isActive()) {
            return new String[0];
        }
        return applicationContext.getBeanDefinitionNames();
    }

    @Override
    public ClassLoader getClassLoader() {
        return coreClassLoader;
    }

    @Override
    public String probe(String contractId) {
        log.info("[lingcore-app] [LingProbe] Health probe ping received, Core baseline is ACTIVE, contract: {}", contractId);
        return "OK";
    }
}
