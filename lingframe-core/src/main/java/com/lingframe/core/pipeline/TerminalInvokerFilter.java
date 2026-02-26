package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.kernel.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TerminalInvokerFilter implements LingInvocationFilter {
    // 方法句柄缓存，避免高频调用时的反射定位开销
    private final Map<String, MethodHandle> handleCache = new ConcurrentHashMap<>();

    @Override
    public int getOrder() {
        return FilterPhase.TERMINAL;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // 模拟场景：如果上游设置了 simulate callable，直接执行模拟逻辑
        @SuppressWarnings("unchecked")
        java.util.concurrent.Callable<Object> simulateCallable = (java.util.concurrent.Callable<Object>) ctx
                .getAttachments().get("ling.simulate.callable");
        if (simulateCallable != null) {
            return simulateCallable.call();
        }

        LingInstance target = (LingInstance) ctx.getAttachments().get("ling.target.instance");
        Class<?>[] resolvedTypes = (Class<?>[]) ctx.getAttachments().get("ling.resolved.types");

        if (target == null || resolvedTypes == null) {
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INTERNAL_ERROR);
        }

        // 1. 获取 Spring 承载的 Bean 实例（在此之前依赖已经被加载且隔离）
        Object serviceBean = getServiceBean(target, ctx.getServiceFQSID().split(":")[1]);
        if (serviceBean == null) {
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        // 2. 解析和缓存 MethodHandle
        String cacheKey = target.getVersion() + "@" + ctx.getServiceFQSID() + "#" + ctx.getMethodName();
        MethodHandle handle = handleCache.computeIfAbsent(cacheKey, k -> {
            try {
                // MethodType 的第一个参数永远是对目标 Bean 实例自己的引用（如果不是静态方法）
                MethodType type = MethodType.methodType(Object.class, resolvedTypes);
                // 使用 publicLookup 有时找不到非公开代理对象，M1/M2阶段暂时降级为基础反射，后期可优化为 Lookup 提速
                java.lang.reflect.Method method = serviceBean.getClass().getMethod(ctx.getMethodName(), resolvedTypes);
                return MethodHandles.publicLookup().unreflect(method);
            } catch (Exception e) {
                throw new RuntimeException("Method resolution failed for " + cacheKey, e);
            }
        });

        // 3. 极速调用
        try {
            if (ctx.getArgs() == null || ctx.getArgs().length == 0) {
                return handle.invoke(serviceBean);
            } else {
                return handle.invokeWithArguments(concatArgs(serviceBean, ctx.getArgs()));
            }
        } catch (Throwable t) {
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INVOKE_ERROR, t);
        }
    }

    private Object[] concatArgs(Object instance, Object[] args) {
        Object[] full = new Object[args.length + 1];
        full[0] = instance;
        System.arraycopy(args, 0, full, 1, args.length);
        return full;
    }

    // TODO: 临时借接手段，M3将重构为彻底从缓存获取 InvokeMethod 元数据而非依赖实例的 Registry
    private Object getServiceBean(LingInstance instance, String serviceName) {
        return null; // M1/M2占位，留给迁移旧逻辑时对接
    }
}
