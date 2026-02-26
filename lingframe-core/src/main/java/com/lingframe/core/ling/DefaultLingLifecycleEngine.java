package com.lingframe.core.ling;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单元生命周期引擎 (V0.3.0)
 * 将生命周期逻辑（装载、隔离、权限申请）从原来的 LingManager 中剥离。
 */
@Slf4j
public class DefaultLingLifecycleEngine implements LingLifecycleEngine {

    private static final int QUEUE_CAPACITY = 100;
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final ContainerFactory containerFactory;
    private final LingLoaderFactory lingLoaderFactory;
    private final PermissionService permissionService;
    private final EventBus eventBus;

    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;

    private final List<LingSecurityVerifier> verifiers;
    private final LingFrameConfig lingFrameConfig;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    private final AtomicInteger globalThreadBudget;
    private final Map<String, Integer> lingThreadAllocations = new ConcurrentHashMap<>();

    private final InstanceCoordinator instanceCoordinator = new InstanceCoordinator();

    public DefaultLingLifecycleEngine(ContainerFactory containerFactory,
            PermissionService permissionService,
            LingLoaderFactory lingLoaderFactory,
            List<LingSecurityVerifier> verifiers,
            EventBus eventBus,
            LingFrameConfig lingFrameConfig,
            LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry) {

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

        this.scheduler = createScheduler();
        this.globalThreadBudget = new AtomicInteger(lingFrameConfig.getGlobalMaxLingThreads());
    }

    @Override
    public void deploy(File lingFile) {
        // V0.3.0 中，部署将由单独的扫描或者 API 传入 LingDefinition
        // 由于向后兼容，我们将继续保持通过定义等进行安装
        throw new UnsupportedOperationException("Use specific deploy methods");
    }

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

            // 在 V0.3.0 中，不再让 runtime 自己 addInstance 绑定 context
            // Context 直接作为全局参数
            LingContext context = new CoreLingContext(lingId, null, permissionService, eventBus);
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

        LingRuntime runtime = lingRepository.deregister(lingId);
        if (runtime == null) {
            log.warn("Ling not found: {}", lingId);
            return;
        }

        // 清理注册表中的暴露条目
        lingServiceRegistry.evict(lingId);

        runtime.getInstancePool().shutdown();

        reclaimThreadBudget(lingId);
        eventBus.unsubscribeAll(lingId);
        permissionService.removeLing(lingId);

        eventBus.publish(new LingUninstalledEvent(lingId));
    }

    private ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-engine-cleaner");
            t.setDaemon(true);
            return t;
        });
    }

    private void reclaimThreadBudget(String lingId) {
        Integer allocated = lingThreadAllocations.remove(lingId);
        if (allocated != null && allocated > 0) {
            globalThreadBudget.addAndGet(allocated);
        }
    }

    private void cleanupOnFailure(ClassLoader classLoader, LingContainer container) {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("Failed to stop container", e);
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
