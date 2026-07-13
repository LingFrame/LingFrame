package com.lingframe.core.proxy;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * 全局服务路由代理。
 * <p>
 * 作用：
 * 1. 作为灵核侧 `@LingReference` 注入的静态入口。
 * 2. 解决“先有代理、后有灵元”的时序问题，使灵元尚未启动时也能先创建代理对象。
 * 3. 在每次调用时动态解析目标灵元，始终路由到当前可用版本。
 * </p>
 * <p>
 * 设计要点：
 * - 不再持有 `Class<?>` 强引用，改为只保存接口名，避免类加载器泄漏。
 * - 通过 `LingRepository` 统一查询运行时，推进中心化路由。
 * - 复用 `SmartServiceProxy` 实例，避免每次调用都重复创建代理对象。
 * </p>
 * <p>
 * 默认路由路径：
 * - 无显式 targetLingId 时使用 {@link #PROVIDER_PLACEHOLDER_LING_ID} 占位符，
 *   使 SmartServiceProxy 自然构造 FQSID = "__provider__:contractId"，
 *   由 {@code ContractProviderRoutingFilter} 在 L0 阶段解析为具体 Provider。
 * - 显式 targetLingId 时仍走老格式 "lingId:contractId"，向后兼容精确 pinning 场景。
 * </p>
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    /**
     * 默认路由路径使用此占位符作为 targetLingId。
     * SmartServiceProxy 拼接 FQSID 后变为 "__provider__:contractId"，
     * 由 ContractProviderRoutingFilter 在 L0 阶段执行 provider 级权重选择。
     */
    private static final String PROVIDER_PLACEHOLDER_LING_ID = "__provider__";

    private final String callerLingId; // 通常为 LingCoreConstants.LINGCORE_LING_ID
    private final String interfaceName; // 仅保存接口全限定名，不持有 Class 对象
    private final String targetLingId; // 用户显式指定的灵元 ID（可选）
    private final LingRepository lingRepository;
    private final InvocationPipelineEngine pipelineEngine;
    // 此字段不再用于反向索引——默认路由改走 __provider__ 占位符。
    // 保留字段与 6 参构造器仅为向后兼容既有调用点，新代码应使用 5 参构造器。
    private final LingServiceRegistry lingServiceRegistry;

    // 复用 SmartServiceProxy，避免每次调用都创建新实例
    private volatile SmartServiceProxy cachedDelegate;
    private volatile String cachedDelegateLingId;

    public GlobalServiceRoutingProxy(String callerLingId, String interfaceName,
                                     String targetLingId, LingRepository lingRepository,
                                     InvocationPipelineEngine pipelineEngine) {
        this(callerLingId, interfaceName, targetLingId, lingRepository, pipelineEngine, null);
    }

    public GlobalServiceRoutingProxy(String callerLingId, String interfaceName,
                                     String targetLingId, LingRepository lingRepository,
                                     InvocationPipelineEngine pipelineEngine,
                                     LingServiceRegistry lingServiceRegistry) {
        this.callerLingId = callerLingId;
        this.interfaceName = interfaceName;
        this.targetLingId = targetLingId;
        this.lingRepository = lingRepository;
        this.pipelineEngine = pipelineEngine;
        this.lingServiceRegistry = lingServiceRegistry;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // `Object` 基础方法直接处理
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 解析目标：显式 targetLingId → 老格式精确 pinning；null → __provider__ 占位符走 L0 路由
        String finalId = resolveTargetLingId();

        // 显式 pinning 路径：保持老语义，预先校验灵元在线状态，离线即抛 STATE_REJECTED
        // __provider__ 占位符路径：跳过预校验——具体 provider 选择与可用性判定交由
        // ContractProviderRoutingFilter 在 pipeline 内统一决策，避免代理层过早查 runtime
        if (!PROVIDER_PLACEHOLDER_LING_ID.equals(finalId)) {
            LingRuntime runtime = lingRepository.getRuntime(finalId);
            if (runtime == null) {
                throw new LingInvocationException(interfaceName, LingInvocationException.ErrorKind.STATE_REJECTED, "Service is currently offline");
            }
        }

        // 复用或创建 `SmartServiceProxy`（占位符路径下 finalId 即 "__provider__"）
        SmartServiceProxy delegate = getOrCreateDelegate(finalId);
        return delegate.invoke(proxy, method, args);
    }

    private SmartServiceProxy getOrCreateDelegate(String lingId) {
        // 快速路径：占位符或具体 lingId 均按字符串相等判断缓存命中
        if (lingId.equals(cachedDelegateLingId) && cachedDelegate != null) {
            return cachedDelegate;
        }
        synchronized (this) {
            if (lingId.equals(cachedDelegateLingId) && cachedDelegate != null) {
                return cachedDelegate;
            }
            cachedDelegate = new SmartServiceProxy(callerLingId, lingId, interfaceName, pipelineEngine);
            cachedDelegateLingId = lingId;
            return cachedDelegate;
        }
    }

    /**
     * 此方法仅做两路分流：
     * <ol>
     *   <li>显式 targetLingId（@LingReference(lingId="...") 锚定）→ 直接返回，走老格式 FQSID</li>
     *   <li>未显式指定 → 返回 {@link #PROVIDER_PLACEHOLDER_LING_ID}，激活 L0 provider 路由</li>
     * </ol>
     * 删除原 O(n) 反向索引 + 字典序取第一个可用灵元的兜底逻辑——多 provider 选择交由
     * {@code ContractProviderRoutingFilter} + {@code ProviderWeightRouter} 在 pipeline 内统一决策，
     * 避免代理层过早选 lingId 与 Dashboard 权重配置脱节。
     */
    private String resolveTargetLingId() {
        // 显式指定灵元 ID：精确 pinning 路径，向后兼容
        if (targetLingId != null && !targetLingId.isEmpty()) {
            return targetLingId;
        }
        // 默认路由路径：占位符交给 pipeline 内的 L0 路由过滤器解析
        return PROVIDER_PLACEHOLDER_LING_ID;
    }
}
