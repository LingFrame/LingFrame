package com.lingframe.core.monitor;

import com.lingframe.core.spi.ThreadLocalPropagator;

public class TraceContextPropagator implements ThreadLocalPropagator<String> {
    @Override
    public String capture() {
        return TraceContext.get();
    }

    @Override
    public String replay(String snapshot) {
        String old = TraceContext.get();
        if (snapshot != null) {
            TraceContext.setTraceId(snapshot);
        }
        return old; // 返回旧值以便恢复
    }

    @Override
    public void restore(String oldTraceId) {
        if (oldTraceId != null) {
            TraceContext.setTraceId(oldTraceId);
        } else {
            TraceContext.clear();
        }
    }
}