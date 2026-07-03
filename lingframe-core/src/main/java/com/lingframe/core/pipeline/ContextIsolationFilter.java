package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

/**
 * 解析隔离过滤器。
 * 负责切换目标 ClassLoader 并补全解析分区。
 * <p>
 * ⚠️ 路由阶段只确定"去哪一个实例"，真正的方法签名解析一定要放到这里做，
 * 因为只有到了这一步，当前线程才拥有目标灵元自己的 ClassLoader 视角。
 */
public class ContextIsolationFilter implements LingInvocationFilter {

    private final LingServiceRegistry serviceRegistry;

    public ContextIsolationFilter(LingServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    public int getOrder() {
        return FilterPhase.RESOLUTION;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        LingInstance target = ctx.routing().getTargetInstance();
        if (target == null) {
            if (ctx.execution().getMode().isSimulation() || ctx.execution().getMode().isGovernOnly()) {
                // 模拟/穿刺模式可能只借道治理，不要求一定存在真实目标实例
                return chain.doFilter(ctx);
            }
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        ClassLoader targetClassLoader = resolveTargetClassLoader(target, ctx);
        InvocationResolutionState resolutionState = ctx.resolution();
        if (resolutionState.getTargetClassName() == null) {
            // 目标类名属于解析协议，不再通过 attachment 字符串 key 传播
            resolutionState.setTargetClassName(resolveTargetClassName(ctx));
        }
        resolutionState.setTargetClassLoader(targetClassLoader);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // ⚠️ 从这里开始切到目标灵元的 TCCL，后续 Method / 参数类型解析都必须在这个宇宙里完成
            Thread.currentThread().setContextClassLoader(targetClassLoader);

            if (resolutionState.getResolvedParameterTypes() == null) {
                resolutionState.setResolvedParameterTypes(
                        InvocationTypeResolver.resolveTypes(ctx.getParameterTypeNames(), targetClassLoader));
            }

            return chain.doFilter(ctx);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private ClassLoader resolveTargetClassLoader(LingInstance target, InvocationContext ctx) {
        try {
            // 只从目标实例读取当前有效的 ClassLoader，不在核心层额外缓存实现侧类型引用
            ClassLoader classLoader = target.getClassLoader();
            if (classLoader == null) {
                throw new IllegalStateException("Target classloader is unavailable");
            }
            return classLoader;
        } catch (Exception e) {
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.CLASSLOADER_ERROR, e);
        }
    }

    /**
     * 解析目标实现类名。
     * <p>
     * 接口服务：FQSID 服务名部分包含 '.'，本身就是接口全限定名，可直接用于 Class.forName。
     * 显式服务：FQSID 服务名部分为短 ID（如 query_user），需从注册表查询实现类全限定名。
     */
    private String resolveTargetClassName(InvocationContext ctx) {
        // FQSID 约定形如 lingId:serviceName[#method]，这里抽出服务类名供后续解析和取 Bean 使用
        String serviceName = ctx.getServiceNameFromFqsid();
        if (serviceName == null) {
            return null;
        }

        // 剥离 '#method' 后缀（如果有）
        String classNamePart = serviceName.contains("#")
                ? serviceName.split("#", 2)[0]
                : serviceName;

        // 接口服务：serviceName 包含 '.'，本身就是类全限定名
        if (classNamePart.contains(".")) {
            return classNamePart;
        }

        // 显式服务：短 ID 不是类名，从注册表获取实现类全限定名
        if (serviceRegistry != null) {
            return serviceRegistry.getImplementationClassName(ctx.getServiceFQSID());
        }
        return null;
    }
}