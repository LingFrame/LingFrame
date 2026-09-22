package com.lingframe.core.ling;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingAlertManager;
import com.lingframe.core.spi.LingGovernanceMetricsCollector;
import com.lingframe.core.spi.LingMetricsCollector;

import java.util.Optional;

public interface LingFrameRuntime extends LingLifecycleEngine {

    LingRepository getRepository();

    LingServiceRegistry getServiceRegistry();

    InvocationPipelineEngine getPipelineEngine();

    EventBus getEventBus();

    /**
     * 治理内核的权限服务契约。
     * <p>
     * 替代外围灵核绕 {@code NativeLingFrame.getLingCoreContext().getPermissionService()} 间接拿权限服务的路径，
     * 让就绪运行时门面直接暴露治理必备契约。
     * <p>
     * 权限服务是灵元装载/卸载治理链路的必备依赖，非可选能力，用强引用。
     */
    PermissionService getPermissionService();

    /**
     * 迁移状态机持有者。
     * <p>
     * 路由层的元状态，由编排层与控制面读写。
     * 默认返回 null（native/test 场景），生产装配层应注入完整实现。
     */
    default MigrationStateHolder getMigrationStateHolder() {
        return null;
    }

    default Optional<LingMetricsCollector> getMetricsCollector() {
        return Optional.empty();
    }

    default Optional<LingGovernanceMetricsCollector> getGovernanceMetricsCollector() {
        return Optional.empty();
    }

    default Optional<LingAlertManager> getAlertManager() {
        return Optional.empty();
    }

    default Optional<LeakDetector> getLeakDetector() {
        return Optional.empty();
    }
}
