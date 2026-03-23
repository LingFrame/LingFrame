package com.lingframe.core.invoker;

import com.lingframe.core.ling.ActiveInvocationSnapshot;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;

final class ActiveInvocationSupport {

    private ActiveInvocationSupport() {
    }

    static ActiveInvocationSnapshot capture(LingInstance instance, String fallbackMethodName) {
        InvocationContext ctx = InvocationContext.current();
        return new ActiveInvocationSnapshot(
                ctx != null ? ctx.getTraceId() : null,
                ctx != null ? ctx.getServiceFQSID() : null,
                firstNonBlank(ctx != null ? ctx.getMethodName() : null, fallbackMethodName),
                ctx != null ? ctx.getCallerLingId() : null,
                ctx != null ? ctx.getResourceId() : null,
                instance != null ? instance.getVersion() : null,
                System.currentTimeMillis(),
                Thread.currentThread().getId(),
                Thread.currentThread().getName());
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        return fallback;
    }
}
