package com.lingframe.core.pipeline;

import com.lingframe.core.exception.LingInvocationException;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerminalInvokerFilter implements LingInvocationFilter {
    private static final Logger log = LoggerFactory.getLogger(TerminalInvokerFilter.class);

    // 全局统一共享的方法执行元数组句柄缓存，由引擎统一管理生命周期
    private final InvokableMethodCache methodCache;

    public TerminalInvokerFilter(InvokableMethodCache methodCache) {
        this.methodCache = methodCache;
    }

    @Override
    public int getOrder() {
        return FilterPhase.TERMINAL;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // 模拟场景：如果上游设置了 simulate callable，直接执行模拟逻辑
        @SuppressWarnings("unchecked")
        Callable<Object> simulateCallable = (Callable<Object>) ctx
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
        Object serviceBean = getServiceBean(target, ctx);
        if (serviceBean == null) {
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        // 2. 解析和缓存 MethodHandle
        String cacheKey = target.getLingId() + ":" + target.getVersion() + "@" + ctx.getServiceFQSID() + "#"
                + ctx.getMethodName();
        MethodHandle handle = methodCache.computeIfAbsent(cacheKey, k -> {
            log.debug("Cache miss for {}, resolving MethodHandle.", cacheKey);
            try {
                // 使用 publicLookup 有时找不到非公开代理对象，M1/M2阶段暂时降级为基础反射，后期可优化为 Lookup 提速
                Method method = serviceBean.getClass().getMethod(ctx.getMethodName(), resolvedTypes);
                MethodHandle mh = MethodHandles.publicLookup().unreflect(method);
                log.trace("Successfully resolved MethodHandle for {}", cacheKey);
                return mh;
            } catch (Exception e) {
                log.error("Failed to resolve MethodHandle for {}", cacheKey, e);
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

    /**
     * 注意 (V0.3.0 架构说明)：
     * 这里通过 Spring Container 懒加载获取真实 Bean 实例，并配合 {@link InvokableMethodCache} 构建句柄。
     * 这是一种故意的防御性设计 (并非早期的临时方案)。
     * 如果在内核层启动或扫描时就直接硬抓取并强引用对方的 Class 或 Bean 实例对象，
     * 旦需要卸载该 Ling 灵元，核心层将由于持有该实例导致目标 ClassLoader 无法被 GC（内存泄漏）。
     * 所以，每次调用时动态从隔离上下文中取壳，获取句柄后再缓存在全局，是保证随时可被安全卸载的终态实践。
     */
    private Object getServiceBean(LingInstance instance, InvocationContext ctx) {
        if (instance == null || instance.getContainer() == null) {
            log.trace("Target instance or container is null for service: {}", ctx.getServiceFQSID());
            return null;
        }

        // 优先从 attachment 获取显式注册的 className
        String className = (String) ctx.getAttachments().get("ling.target.className");
        if (className != null) {
            try {
                Class<?> clazz = instance.getContainer().getClassLoader().loadClass(className);
                return instance.getContainer().getBean(clazz);
            } catch (Exception e) {
                log.warn("Failed to get bean by explicit className: {}", className, e);
            }
        }

        String serviceName = ctx.getServiceFQSID().split(":")[1];
        try {
            // 如果含有 # 说明是类全限定名形式的直接请求
            if (serviceName.contains("#")) {
                serviceName = serviceName.split("#")[0];
            }
            Class<?> clazz = instance.getContainer().getClassLoader().loadClass(serviceName);
            return instance.getContainer().getBean(clazz);
        } catch (ClassNotFoundException e) {
            // 降级使用 Bean Name 获取
            try {
                return instance.getContainer().getBean(serviceName);
            } catch (Exception ex) {
                log.trace("Caught exception while fetching service bean '{}' from container", serviceName, ex);
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
