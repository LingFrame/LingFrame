package com.lingframe.api.context;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 灵元调用上下文（对外最小上下文）
 * 提供基于 ThreadLocal 的当前调用链路上下文（灵元ID、灰度标签、TraceId 等）存取支持。
 */
public final class LingCallContext {
    private LingCallContext() {
    }

    private static final ThreadLocal<String> LING_ID = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> LABELS = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    public static void setLingId(String lingId) {
        LING_ID.set(lingId);
    }

    public static String getLingId() {
        return LING_ID.get();
    }

    public static void setLabels(Map<String, String> labels) {
        LABELS.set(labels);
    }

    public static Map<String, String> getLabels() {
        return LABELS.get();
    }

    public static String startTrace() {
        String tid = TRACE_ID.get();
        if (tid == null) {
            tid = generateTraceId();
            TRACE_ID.set(tid);
        }
        return tid;
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.trim().isEmpty()) {
            TRACE_ID.set(traceId);
        } else {
            startTrace();
        }
    }

    public static String generateTraceId() {
        return String.format("%016X", ThreadLocalRandom.current().nextLong());
    }

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
        LING_ID.remove();
        LABELS.remove();
        TRACE_ID.remove();
        DEPTH.remove();
    }

    public static void clearTraceId() {
        TRACE_ID.remove();
        DEPTH.remove();
    }
}
