package com.lingframe.core.proxy;

import com.lingframe.api.context.LingContextHolder;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能动态代理
 * 特性：不再持有任何 LingRuntime / GovernanceKernel 强引用，
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
        final InvocationContext finalCtx = ctx;

        try {
            finalCtx.setTraceId(null);
            finalCtx.setCallerLingId(this.callerLingId);
            finalCtx.setTargetLingId(this.targetLingId);
            finalCtx.setMethodName(method.getName());

            // 提取参数签名类型
            Class<?>[] pTypes = method.getParameterTypes();
            String[] pTypeNames = new String[pTypes.length];
            for (int i = 0; i < pTypes.length; i++) {
                pTypeNames[i] = pTypes[i].getName();
            }
            // V0.3.0 新增 Pipeline 参数属性，此时放在 attachment 中暂存（M3之后改到 Context 原生字段）
            finalCtx.getAttachments().put("ling.method.paramTypes", pTypes);

            finalCtx.setOperation(method.getName());
            finalCtx.setArgs(args);
            finalCtx.setResourceType("RPC");

            Map<String, String> labels = LingContextHolder.getLabels();
            finalCtx.setLabels(labels != null ? labels : Collections.emptyMap());

            String resourceId = resourceIdCache.computeIfAbsent(method,
                    m -> m.getDeclaringClass().getName() + ":" + m.getName());
            finalCtx.setResourceId(resourceId);

            // 组装最终的目标服务寻址标： `targetLingId:interfaceFQCN`
            finalCtx.setServiceFQSID(targetLingId + ":" + method.getDeclaringClass().getName());

            finalCtx.setAccessType(AccessType.EXECUTE);
            finalCtx.setAuditAction(resourceId);
            finalCtx.setMetadata(null);

            // 委托全局的 PipelineEngine 执行
            if (pipelineEngine != null) {
                return pipelineEngine.invoke(finalCtx);
            } else {
                throw new IllegalStateException("PipelineEngine is not initialized for proxy.");
            }

        } catch (ProxyExecutionException e) {
            // 解包并抛出原始异常，对调用者透明
            throw e.getCause();
        } finally {
            // 彻底重置 ThreadLocal 池化上下文，防止污染同线程下次调用
            finalCtx.reset();
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