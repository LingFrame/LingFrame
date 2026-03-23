package com.lingframe.core.ling;

/**
 * 用于 drain 可观测性的轻量级在途调用快照。
 * 该对象只保留字符串和时间戳，避免持有重量级运行时引用。
 */
public final class ActiveInvocationSnapshot {

    private final String traceId;
    private final String serviceFQSID;
    private final String methodName;
    private final String callerLingId;
    private final String resourceId;
    private final String instanceVersion;
    private final long startTimeMillis;
    private final long threadId;
    private final String threadName;

    public ActiveInvocationSnapshot(String traceId,
                                    String serviceFQSID,
                                    String methodName,
                                    String callerLingId,
                                    String resourceId,
                                    String instanceVersion,
                                    long startTimeMillis,
                                    long threadId,
                                    String threadName) {
        this.traceId = traceId;
        this.serviceFQSID = serviceFQSID;
        this.methodName = methodName;
        this.callerLingId = callerLingId;
        this.resourceId = resourceId;
        this.instanceVersion = instanceVersion;
        this.startTimeMillis = startTimeMillis;
        this.threadId = threadId;
        this.threadName = threadName;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getServiceFQSID() {
        return serviceFQSID;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getCallerLingId() {
        return callerLingId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getInstanceVersion() {
        return instanceVersion;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getThreadId() {
        return threadId;
    }

    public String getThreadName() {
        return threadName;
    }

    public long ageMillis(long nowMillis) {
        return Math.max(0L, nowMillis - startTimeMillis);
    }

    public String toSummary(long nowMillis) {
        return String.format(
                "traceId=%s, service=%s, method=%s, resource=%s, caller=%s, version=%s, ageMs=%d, thread=%s(%d)",
                display(traceId),
                display(serviceFQSID),
                display(methodName),
                display(resourceId),
                display(callerLingId),
                display(instanceVersion),
                ageMillis(nowMillis),
                display(threadName),
                threadId);
    }

    private String display(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
