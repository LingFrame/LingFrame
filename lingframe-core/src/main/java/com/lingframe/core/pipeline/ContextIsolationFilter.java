package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

/**
 * ClassLoader 隔离 Filter。
 * 切换线程上下文 ClassLoader 至目标灵元，解析方法签名类型，
 * 执行完毕后主动清除瞬态引用防止内存泄漏。
 */
public class ContextIsolationFilter implements LingInvocationFilter {
    @Override
    public int getOrder() {
        return FilterPhase.ISOLATION;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        LingInstance target = (LingInstance) ctx.getAttachments().get("ling.target.instance");
        if (target == null) {
            if (ctx.isDryRun()) {
                return chain.doFilter(ctx);
            }
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        // 通过 Container 获取 ClassLoader，不再使用反射
        ClassLoader lingClassLoader;
        try {
            lingClassLoader = target.getContainer().getClassLoader();
        } catch (Exception e) {
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.CLASSLOADER_ERROR, e);
        }

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // 1. 切换线程上下文 ClassLoader
            Thread.currentThread().setContextClassLoader(lingClassLoader);

            // 2. 根据 String[] names 解析 Class<?>[] 对象 (在隔离 ClassLoader 下执行)
            Class<?>[] resolvedTypes = resolveTypes(ctx.getParameterTypeNames(), lingClassLoader);
            ctx.getAttachments().put("ling.resolved.types", resolvedTypes);

            // 3. 放行给 TerminalInvokerFilter
            return chain.doFilter(ctx);
        } finally {
            // ！！！铁律：绝对禁止跨调用栈泄漏业务对象引用！！！
            ctx.getAttachments().remove("ling.target.instance");
            ctx.getAttachments().remove("ling.resolved.types");
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private Class<?>[] resolveTypes(String[] typeNames, ClassLoader cl) throws ClassNotFoundException {
        if (typeNames == null || typeNames.length == 0)
            return new Class<?>[0];
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = loadClass(typeNames[i], cl);
        }
        return types;
    }

    private Class<?> loadClass(String typeName, ClassLoader cl) throws ClassNotFoundException {
        switch (typeName) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "double":
                return double.class;
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "float":
                return float.class;
            case "char":
                return char.class;
            default:
                return Class.forName(typeName, false, cl);
        }
    }
}
