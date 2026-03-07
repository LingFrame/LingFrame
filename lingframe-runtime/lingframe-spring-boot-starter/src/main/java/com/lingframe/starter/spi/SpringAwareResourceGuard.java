package com.lingframe.starter.spi;

import com.lingframe.core.spi.ResourceGuard;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 支持 Spring Context 感知与预清理的增强 ResourceGuard 契约。
 * <p>
 * 凡实现该接口的资源守卫，在 Spring 灵元卸载第一阶段（Context 活跃时期）将收到注入的上下文，并获得
 * {@link #preCleanup(String)} 预清理执行机会。
 * </p>
 */
public interface SpringAwareResourceGuard extends ResourceGuard {

    /**
     * 接收并注入 Spring 上下文引用
     *
     * @param mainContext 主容器（宿主）上下文
     * @param lingContext 灵元子容器上下文
     */
    void setContexts(ApplicationContext mainContext, ConfigurableApplicationContext lingContext);

    /**
     * 在 Spring Context 关闭前、BeanFactory 等仍在活跃状态时触发预清理。
     * 可执行包括扫描缓存、操作特定 Bean，移除 Listener 等必须在关闭前完成的动作。
     *
     * @param lingId 灵元 ID
     */
    void preCleanup(String lingId);

    /**
     * 清空上下文引用，防止单例 Guard 持有已卸载灵元的 Context/ClassLoader 导致内存泄漏。
     * 应在每次 cleanup 完成后由容器调用。
     */
    void clearContexts();
}
