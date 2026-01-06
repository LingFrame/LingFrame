package com.lingframe.core.monitor;

import java.util.UUID;

/**
 * 链路追踪上下文
 * 使用 ThreadLocal 管理 TraceId，确保在异步调用中传递链路上下文。
 */
public class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    // 调用深度计数器
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * 开启或获取当前 TraceId
     */
    public static String start() {
        String tid = TRACE_ID.get();
        if (tid == null) {
            tid = UUID.randomUUID().toString().replace("-", "");
            TRACE_ID.set(tid);
        }
        return tid;
    }

    public static String get() {
        return TRACE_ID.get();
    }

    /**
     * 允许手动设置 TraceId (用于从 Web Header 继承)
     *
     */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            TRACE_ID.set(traceId);
        } else {
            // 如果传入空，则生成一个新的
            start();
        }
    }

    /**
     * 为了兼容性，提供 getTraceId 别名
     */
    public static String getTraceId() {
        return TRACE_ID.get();
    }

    // ========== 深度管理 ==========

    public static int getDepth() {
        return DEPTH.get() == null ? 0 : DEPTH.get();
    }

    public static void increaseDepth() {
        DEPTH.set(getDepth() + 1);
    }

    public static void decreaseDepth() {
        DEPTH.set(Math.max(0, getDepth() - 1));
    }

    public static void clear() {
        TRACE_ID.remove();
        DEPTH.remove();
    }

}