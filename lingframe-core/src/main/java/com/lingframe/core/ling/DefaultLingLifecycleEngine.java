package com.lingframe.core.ling;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.*;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;

/**
 * 单元生命周期引擎 (V0.3.0)
 * 将生命周期逻辑（装载、隔离、权限申请）从原来的 LingManager 中剥离。
 */
@Slf4j
public class DefaultLingLifecycleEngine implements LingLifecycleEngine {

    private final ContainerFactory containerFactory;
    private final LingLoaderFactory lingLoaderFactory;
    private final PermissionService permissionService;
    private final EventBus eventBus;

    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;

    private final List<LingSecurityVerifier> verifiers;
    private final LingFrameConfig lingFrameConfig;
    private final InvocationPipelineEngine pipelineEngine;
    private final ResourceGuard resourceGuard;

    private final InstanceCoordinator instanceCoordinator = new InstanceCoordinator();

    public DefaultLingLifecycleEngine(ContainerFactory containerFactory,
            PermissionService permissionService,
            LingLoaderFactory lingLoaderFactory,
            List<LingSecurityVerifier> verifiers,
            EventBus eventBus,
            LingFrameConfig lingFrameConfig,
            LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            InvocationPipelineEngine pipelineEngine,
            ResourceGuard resourceGuard) {

        this.containerFactory = containerFactory;
        this.lingLoaderFactory = lingLoaderFactory;
        this.permissionService = permissionService;
        this.eventBus = eventBus;

        this.verifiers = new ArrayList<>();
        if (verifiers != null) {
            this.verifiers.addAll(verifiers);
        }
        boolean hasBytecodeVerifier = this.verifiers.stream()
                .anyMatch(v -> v instanceof DangerousApiVerifier);
        if (!hasBytecodeVerifier) {
            this.verifiers.add(new DangerousApiVerifier());
        }

        this.lingFrameConfig = lingFrameConfig;
        this.lingRepository = lingRepository;
        this.lingServiceRegistry = lingServiceRegistry;
        this.pipelineEngine = pipelineEngine;
        this.resourceGuard = resourceGuard;
    }

    @Override
    public void deploy(LingDefinition lingDefinition, File sourceFile, boolean isDefault, Map<String, String> labels) {
        lingDefinition.validate();
        String lingId = lingDefinition.getId();
        String version = lingDefinition.getVersion();
        eventBus.publish(new LingInstallingEvent(lingId, version, sourceFile));

        ClassLoader lingClassLoader = null;
        LingContainer container = null;
        boolean isNewRuntime = false;
        try {
            for (LingSecurityVerifier verifier : verifiers) {
                verifier.verify(lingId, sourceFile);
            }

            if (lingRepository.hasRuntime(lingId)) {
                log.info("[{}] Preparing for upgrade", lingId);
            } else {
                isNewRuntime = true;
            }

            lingClassLoader = lingLoaderFactory.create(lingId, sourceFile, getClass().getClassLoader());
            container = containerFactory.create(lingId, sourceFile, lingClassLoader);

            LingDefinition instanceDef = lingDefinition.copy();
            LingInstance instance = new LingInstance(container, instanceDef);
            instance.addLabels(labels);

            // FSM state flow to loading
            instanceCoordinator.prepare(instance);

            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                runtime = new LingRuntime(lingId, lingFrameConfig.getRuntimeConfig());
                lingRepository.register(runtime);
            }

            LingContext context = new CoreLingContext(lingId, lingRepository, lingServiceRegistry, pipelineEngine,
                    permissionService, eventBus);
            instanceCoordinator.start(instance);

            // 启动灵元 Spring 容器（创建 Bean、注册 Controller、扫描 LingService）
            container.start(context);

            runtime.getInstancePool().addInstance(instance, isDefault);
            instanceCoordinator.markReady(instance);

            if (lingDefinition.getGovernance() != null
                    && lingDefinition.getGovernance().getCapabilities() != null) {
                for (GovernancePolicy.CapabilityRule rule : lingDefinition.getGovernance()
                        .getCapabilities()) {
                    try {
                        AccessType accessType = AccessType.valueOf(rule.getAccessType().toUpperCase());
                        permissionService.grant(lingId, rule.getCapability(), accessType);
                    } catch (IllegalArgumentException e) {
                        log.warn("[{}] Invalid access type: {}", lingId, rule.getAccessType());
                    }
                }
            }

            // 实例就绪后，将状态机从 INACTIVE → ACTIVE
            if (runtime.getStateMachine().current() == RuntimeStatus.INACTIVE) {
                runtime.getStateMachine().transition(RuntimeStatus.ACTIVE);
                log.info("[{}] State transitioned to ACTIVE", lingId);
            }

            eventBus.publish(new LingInstalledEvent(lingId, version));
            log.info("[{}] Installed successfully", lingId);

        } catch (Throwable t) {
            log.error("Failed to install ling: {} v{}", lingId, version, t);

            if (isNewRuntime) {
                lingRepository.deregister(lingId);
            }

            cleanupOnFailure(lingClassLoader, container);
            throw t;
        }
    }

    @Override
    public void undeploy(String lingId) {
        log.info("Uninstalling ling: {}", lingId);
        eventBus.publish(new LingUninstallingEvent(lingId));

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            log.warn("Ling not found: {}", lingId);
            return;
        }

        // 1. 改变宏观状态，拒绝新请求
        if (runtime.getStateMachine().current() != RuntimeStatus.STOPPING) {
            runtime.getStateMachine().transition(RuntimeStatus.STOPPING);
        }

        // 2. 预留：等待存量请求排空 (当前仅为简单日志，实际应结合监控指标)
        log.info("[{}] Draining existing requests...", lingId);

        // 3. 逐个卸载底层实例
        List<LingInstance> instances = runtime.getInstancePool().getAllInstances();
        for (LingInstance instance : instances) {
            instanceCoordinator.tearDown(instance, eventBus);
            runtime.getInstancePool().removeInstance(instance);

            // 🔥 彻底卸载的关键：清理资源并检测泄漏
            ClassLoader classLoader = instance.getContainer().getClassLoader();
            if (resourceGuard != null) {
                resourceGuard.cleanup(lingId, classLoader);
            }

            // 显式关闭类加载器 (释放 Jar 句柄)
            if (classLoader instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) classLoader).close();
                } catch (Exception e) {
                    log.error("[{}] Failed to close ClassLoader", lingId, e);
                }
            }

            // 延迟触发泄漏检测
            if (resourceGuard != null) {
                resourceGuard.detectLeak(lingId, classLoader);
            }
        }

        // 4. 清理注册表中的暴露条目
        lingServiceRegistry.evict(lingId);

        // 5. 彻底解绑监听与权限
        eventBus.unsubscribeAll(lingId);
        permissionService.removeLing(lingId);

        // 6. 宣告生命终结
        runtime.getStateMachine().transition(RuntimeStatus.REMOVED);

        // 7. 从仓储拔除引用
        lingRepository.deregister(lingId);

        eventBus.publish(new LingUninstalledEvent(lingId));
    }

    private void cleanupOnFailure(ClassLoader classLoader, LingContainer container) {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("Failed to stop container", e);
            }
        }

        if (resourceGuard != null && classLoader != null) {
            try {
                resourceGuard.cleanup("fault-cleanup", classLoader);
            } catch (Exception e) {
                log.warn("ResourceGuard cleanup failed during failure recovery", e);
            }
        }

        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                log.warn("Failed to close classloader", e);
            }
        }
    }
}
