package com.lingframe.core.proxy;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

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
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    private final String callerLingId; // 通常为 "lingcore-app"
    private final String interfaceName; // 仅保存接口全限定名，不持有 Class 对象
    private final String targetLingId; // 用户显式指定的灵元 ID（可选）
    private final LingRepository lingRepository;
    private final InvocationPipelineEngine pipelineEngine;
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

        // 实时解析目标灵元 ID
        String finalId = resolveTargetLingId();

        LingRuntime runtime = (finalId != null) ? lingRepository.getRuntime(finalId) : null;
        if (runtime == null) {
            throw new LingInvocationException(interfaceName, LingInvocationException.ErrorKind.STATE_REJECTED, "Service is currently offline");
        }

        // 复用或创建 `SmartServiceProxy`
        SmartServiceProxy delegate = getOrCreateDelegate(runtime.getLingId());
        return delegate.invoke(proxy, method, args);
    }

    private SmartServiceProxy getOrCreateDelegate(String lingId) {
        // 快速路径：如果目标灵元 ID 未变化，直接复用已有代理
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

    private String resolveTargetLingId() {
        // 如果注解已显式指定灵元 ID，直接使用
        if (targetLingId != null && !targetLingId.isEmpty()) {
            return targetLingId;
        }

        // 路由收敛：删原 O(n) 遍历全仓 + 反射 loadClass + getBean 的兜底逻辑，
        // 改走 LingServiceRegistry 反向索引 O(1) 命中。
        // 语义变化：未注册的契约不再兜底查到，返回 null 由上层抛 STATE_REJECTED。
        // 这是有意行为——implicit-registration: false 时未标 @LingService 但 implements 的灵元
        // 不应被路由命中，避免误回退。
        if (lingServiceRegistry == null) {
            return null;
        }
        List<String> lingIds = lingServiceRegistry.getLingIdsByContractId(interfaceName);
        if (lingIds == null || lingIds.isEmpty()) {
            // 兜底：interfaceName 可能是完整 FQSID（含 ':'），反向索引键只存 contractId 部分
            int idx = interfaceName.indexOf(':');
            if (idx > 0 && idx < interfaceName.length() - 1) {
                String contractId = interfaceName.substring(idx + 1);
                lingIds = lingServiceRegistry.getLingIdsByContractId(contractId);
            }
        }
        if (lingIds == null || lingIds.isEmpty()) {
            return null;
        }
        // 多灵元命中时记调试日志，便于路由排查——删 O(n) 遍历后丢过这个线索。
        if (lingIds.size() > 1) {
            log.debug("Multiple lings matched for contract [{}]: {}, routing to first available",
                    interfaceName, lingIds);
        }
        // 多灵元命中时取第一个可用灵元；排序保证稳定（按字典序）。
        // 负载均衡策略暂不抽象——当前单实例/少实例阶段字典序足够；
        // 真正需要轮询/加权/最少连接时再引入 RoutingStrategy 接口，避免过早抽象。
        for (String lingId : lingIds.stream().sorted().collect(Collectors.toList())) {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime != null && runtime.isAvailable()) {
                return lingId;
            }
        }
        return null;
    }
}
