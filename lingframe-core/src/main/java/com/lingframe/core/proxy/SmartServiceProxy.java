package com.lingframe.core.proxy;

import com.lingframe.api.context.LingContextHolder;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.monitor.TraceContext;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能动态代理
 * 特性：不再持有任何 LingRuntime 强引用，
 * 仅通过字符串元数据和全局 Pipeline 交互，彻底杜绝 ClassLoader 泄漏连带。
 */
@Slf4j
public class SmartServiceProxy implements InvocationHandler {

    private final String callerLingId; // 谁在调用
    private final String targetLingId; // 目标灵元 (仅存 ID 字符串)

    // 全局流水线引擎 (复用单例实例引用，无状态，不涉猎特定组件 ClassLoader)
    private final InvocationPipelineEngine pipelineEngine;

    // 获取服务 FQSID 等的临时缓存（Method 实例作为 Key 仍会持有类引用，但这是调用方自己定义的接口，因此与被调用方无关）
    private final Map<Method, String> resourceIdCache = new ConcurrentHashMap<>();

    public SmartServiceProxy(String callerLingId,
            String targetLingId,
            InvocationPipelineEngine pipelineEngine) {
        this.callerLingId = callerLingId;
        this.targetLingId = targetLingId;
        this.pipelineEngine = pipelineEngine;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        InvocationContext ctx = InvocationContext.obtain();

        try {
            ctx.setTraceId(TraceContext.get());
            ctx.setCallerLingId(this.callerLingId);
            ctx.setTargetLingId(this.targetLingId);
            ctx.setMethodName(method.getName());

            // 提取参数签名类型 (必须转为 String[] 以防跨线程持有的 Class 对象导致 ClassLoader 泄漏)
            Class<?>[] pTypes = method.getParameterTypes();
            String[] pTypeNames = new String[pTypes.length];
            for (int i = 0; i < pTypes.length; i++) {
                pTypeNames[i] = pTypes[i].getName();
            }
            ctx.setParameterTypeNames(pTypeNames);

            ctx.setOperation(method.getName());
            ctx.setArgs(args);
            ctx.setResourceType("RPC");

            Map<String, String> labels = LingContextHolder.getLabels();
            ctx.setLabels(labels != null ? labels : Collections.emptyMap());

            String resourceId = resourceIdCache.computeIfAbsent(method,
                    m -> m.getDeclaringClass().getName() + ":" + m.getName());
            ctx.setResourceId(resourceId);

            // 组装最终的目标服务寻址标： `targetLingId:interfaceFQCN`
            ctx.setServiceFQSID(targetLingId + ":" + method.getDeclaringClass().getName());

            ctx.setAccessType(AccessType.EXECUTE);
            ctx.setAuditAction(resourceId);
            ctx.setMetadata(null);

            // 委托全局的 PipelineEngine 执行
            if (pipelineEngine != null) {
                return pipelineEngine.invoke(ctx);
            } else {
                throw new IllegalStateException("PipelineEngine is not initialized for proxy.");
            }

        } catch (ProxyExecutionException e) {
            // 解包并抛出原始异常，对调用者透明
            throw e.getCause();
        } finally {
            // 彻底重置 ThreadLocal 池化上下文，归还到对象栈中
            ctx.recycle();
        }
    }

    /**
     * 内部异常包装器 (用于穿透 Lambda，Kernel 捕获后会透传回来)
     */
    private static class ProxyExecutionException extends RuntimeException {
        public ProxyExecutionException(Throwable cause) {
            super(cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // 优化：禁用异常栈收集。这个异常仅仅是作为穿透 Callable/Lambda 的载体，
            // 收集当前代理层的栈没有业务意义，禁用以获得极致性能。
            return this;
        }
    }

}