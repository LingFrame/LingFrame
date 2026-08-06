package com.lingframe.core.proxy;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.RoutableTarget;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;

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
 * 路由路径：
 * - 无显式 targetLingId 时 SmartServiceProxy 组装的 FQSID 直接为裸 contractId（接口全限定名），
 *   由 {@code ContractProviderRoutingFilter} 在 L0 阶段按 provider 权重选中具体 provider。
 *   此时 {@code ctx.getTargetLingId()} 保持 null，触发 L0 路由。
 * - 显式 targetLingId 时走老格式 "lingId:contractId"，向后兼容精确 pinning 场景。
 *   代理层此路径下预校验灵元在线状态，离线即抛 STATE_REJECTED。
 * </p>
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    private final String callerLingId; // 通常为 LingCoreConstants.LINGCORE_LING_ID
    private final String interfaceName; // 仅保存接口全限定名，不持有 Class 对象
    private final String targetLingId; // 用户显式指定的灵元 ID（可选；null 表示走 L0 默认路由）
    private final LingRepository lingRepository;
    private final InvocationPipelineEngine pipelineEngine;

    // 复用 SmartServiceProxy，避免每次调用都创建新实例
    // cachedDelegateKey 区分两条路径：显式 lingId 走老格式拼装，null 走裸 contractId
    private volatile SmartServiceProxy cachedDelegate;
    private volatile String cachedDelegateKey;

    public GlobalServiceRoutingProxy(String callerLingId, String interfaceName,
                                     String targetLingId, LingRepository lingRepository,
                                     InvocationPipelineEngine pipelineEngine) {
        this(callerLingId, interfaceName, targetLingId, lingRepository, pipelineEngine, null);
    }

    /** 兼容旧 6 参构造点：lingServiceRegistry 参数已退役，忽略 */
    public GlobalServiceRoutingProxy(String callerLingId, String interfaceName,
                                     String targetLingId, LingRepository lingRepository,
                                     InvocationPipelineEngine pipelineEngine,
                                     LingServiceRegistry lingServiceRegistry) {
        this.callerLingId = callerLingId;
        this.interfaceName = interfaceName;
        this.targetLingId = (targetLingId != null && !targetLingId.isEmpty()) ? targetLingId : null;
        this.lingRepository = lingRepository;
        this.pipelineEngine = pipelineEngine;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 基础方法直接处理
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 显式 pinning 路径：保持老语义，预先校验目标在线状态，离线即抛 STATE_REJECTED
        // 默认路由路径（targetLingId == null）：跳过预校验，交由 L0 路由过滤器统一决策
        // 路由升维：用 getRoutableTarget 统一覆盖灵元（LingRuntime）与灵核（LingCoreRoutableTarget），
        // 修复灵元 delegate 到灵核（@LingReference(lingId="lingcore-app")）时 getRuntime 返回 null 误判离线的问题
        if (targetLingId != null) {
            RoutableTarget target = lingRepository.getRoutableTarget(targetLingId);
            if (target == null) {
                throw new LingInvocationException(interfaceName,
                        LingInvocationException.ErrorKind.STATE_REJECTED,
                        "Service is currently offline");
            }
        }

        SmartServiceProxy delegate = getOrCreateDelegate();
        return delegate.invoke(proxy, method, args);
    }

    private SmartServiceProxy getOrCreateDelegate() {
        // 缓存键：显式 lingId 用 lingId 本身；null 走占位标记，避免 null 与空串混淆
        String key = targetLingId != null ? targetLingId : "__default_route__";
        if (key.equals(cachedDelegateKey) && cachedDelegate != null) {
            return cachedDelegate;
        }
        synchronized (this) {
            if (key.equals(cachedDelegateKey) && cachedDelegate != null) {
                return cachedDelegate;
            }
            // targetLingId 为 null 时 SmartServiceProxy 会组装裸 contractId 作为 FQSID
            cachedDelegate = new SmartServiceProxy(callerLingId, targetLingId, interfaceName, pipelineEngine);
            cachedDelegateKey = key;
            return cachedDelegate;
        }
    }
}
