package com.lingframe.core.pipeline;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.router.ProviderWeightRouter;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.spi.TrafficRouter;
import lombok.Builder;
import lombok.Getter;

/**
 * {@link FilterRegistry} 的装配参数对象。
 * <p>
 * 收敛原 3 个构造器 + 4 个 {@code initialize()} 重载的两阶段初始化问题，
 * 一次性完成构建与初始化，杜绝"构造了但未 initialize"的半成品状态。
 * <p>
 * 设计意图：
 * <ul>
 *   <li>单一构造入口，phase 契约校验在构造器内 fail-fast 执行</li>
 *   <li>消除构造器与 initialize 参数交叉导致的认知负担</li>
 *   <li>可选组件用 {@code @Builder.Default} 默认 null，由 Registry 内部兜底</li>
 * </ul>
 */
@Builder
@Getter
public class FilterRegistryConfig {

    // ==================== 构造期（强依赖） ====================

    private final InvokableMethodCache methodCache;
    private final PermissionService permissionService;

    // ==================== 构造期（可选，默认 null） ====================

    @Builder.Default
    private final LingServiceInvoker serviceInvoker = null;
    @Builder.Default
    private final GovernanceArbitrator governanceArbitrator = null;

    // ==================== 初始化期 ====================

    private final LingRepository lingRepository;
    private final TrafficRouter trafficRouter;
    private final EventBus eventBus;

    @Builder.Default
    private final LingServiceRegistry serviceRegistry = null;
    @Builder.Default
    private final MetricsCollector metricsCollector = null;
    @Builder.Default
    private final RuntimeCoordinator runtimeCoordinator = null;
    @Builder.Default
    private final GovernanceMetricsCollector governanceMetricsCollector = null;

    /**
     * 治理补丁注册表，供 {@link InvocationPolicyPrefillFilter} 读取动态补丁。
     * 可为 null（native/test 场景未注入时，预填充 filter 只读静态策略，不读 patch）。
     */
    @Builder.Default
    private final LocalGovernanceRegistry governanceRegistry = null;

    /**
     * 灵核全局配置只读门面，供 {@link PermissionGovernanceFilter} 判断 dev/prod 模式。
     * 可为 null（native/test 场景未注入时，过滤器按 prod 模式 Deny-by-Default 处理）。
     */
    @Builder.Default
    private final LingFrameInfo lingFrameInfo = null;

    /**
     * L0 provider 级权重路由器，供 {@link ContractProviderRoutingFilter} 按权重选择 provider。
     * 可为 null（native/test 场景未注入时，Registry 内部创建默认实例，使用默认权重 CORE=100/LING=0）。
     */
    @Builder.Default
    private final ProviderWeightRouter providerWeightRouter = null;
}
