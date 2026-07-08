package com.lingframe.starter.spi;

import com.lingframe.core.spi.LingUnloadHook;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 支持 Spring Context 感知与预清理的增强 LingUnloadHook 契约。
 * <p>
 * 凡实现该接口的卸载钩子，在 Spring 灵元卸载第一阶段（Context 活跃时期）将收到
 * 以参数形式传入的上下文，并获得 {@link #preCleanup} 预清理执行机会。
 * </p>
 * <p>
 * 设计原则：实现类不再持有 mainContext/lingContext 的可变单例字段，
 * 上下文以方法参数传递，从根源上消除并发卸载时单例字段被覆盖的竞态。
 * </p>
 */
public interface SpringAwareUnloadHook extends LingUnloadHook {

    /**
     * 在 Spring Context 关闭前、BeanFactory 等仍在活跃状态时触发预清理。
     * 可执行包括扫描缓存、操作特定 Bean，移除 Listener、移除 ShutdownHook 等
     * 必须在关闭前完成的动作。
     *
     * @param lingId      灵元 ID
     * @param mainContext 主容器（灵核）上下文
     * @param lingContext 灵元子容器上下文
     */
    void preCleanup(String lingId, ApplicationContext mainContext,
                    ConfigurableApplicationContext lingContext);
}
