package com.lingframe.core.ling;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingAlertManager;
import com.lingframe.core.spi.LingGovernanceMetricsCollector;
import com.lingframe.core.spi.LingHotSwapWatcher;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingMetricsCollector;
import com.lingframe.core.spi.LingSecurityVerifier;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * {@link DefaultLingLifecycleEngine} 的装配参数对象。
 * <p>
 * 强依赖走 Builder 必填字段，可选组件走 {@code @Builder.Default} 默认 null。
 * <p>
 * 设计意图：
 * <ul>
 *   <li>单一构造入口，杜绝"半初始化状态"（构造了但漏调 setter）</li>
 *   <li>可选项显式化，装配层一目了然哪些是核心依赖、哪些是扩展组件</li>
 *   <li>可测试性：测试只需构造必要的强依赖，可选项默认 null 不影响核心流程</li>
 * </ul>
 */
@Builder
@Getter
public class LifecycleEngineConfig {

    // ==================== 强依赖（必填） ====================

    private final ContainerFactory containerFactory;
    private final PermissionService permissionService;
    private final LingLoaderFactory lingLoaderFactory;
    private final List<LingSecurityVerifier> verifiers;
    private final EventBus eventBus;
    private final LingFrameConfig lingFrameConfig;
    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;
    private final InvocationPipelineEngine pipelineEngine;
    private final LingResourceManager lingResourceManager;
    private final LingUnloadCoordinator unloadCoordinator;
    private final RuntimeCoordinator runtimeCoordinator;

    // ==================== 可选依赖（默认 null / 默认值，装配层按需注入） ====================

    /** 生命周期互斥锁获取超时（毫秒），默认 120s，应大于最大 forceCleanupDelaySeconds */
    @Builder.Default
    private final long lifecycleLockTimeoutMs = 120_000L;

    @Builder.Default
    private final LingHotSwapWatcher hotSwapWatcher = null;
    @Builder.Default
    private final MigrationStateHolder migrationStateHolder = null;
    @Builder.Default
    private final LingMetricsCollector metricsCollector = null;
    @Builder.Default
    private final LingGovernanceMetricsCollector governanceMetricsCollector = null;
    @Builder.Default
    private final LingAlertManager alertManager = null;
    @Builder.Default
    private final LeakDetector leakDetector = null;
}
