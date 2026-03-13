package com.lingframe.core.pipeline;

import com.lingframe.api.context.LingCallContext;

import java.util.HashMap;
import java.util.Map;

final class LingCallContextSnapshot {
    private final String lingId;
    private final Map<String, String> labels;
    private final String traceId;

    private LingCallContextSnapshot(String lingId, Map<String, String> labels, String traceId) {
        this.lingId = lingId;
        this.labels = labels;
        this.traceId = traceId;
    }

    static LingCallContextSnapshot capture() {
        String lingId = LingCallContext.getLingId();
        Map<String, String> labels = LingCallContext.getLabels();
        Map<String, String> labelsCopy = labels != null ? new HashMap<>(labels) : null;
        String traceId = LingCallContext.getTraceId();
        return new LingCallContextSnapshot(lingId, labelsCopy, traceId);
    }

    static LingCallContextSnapshot apply(LingCallContextSnapshot snapshot) {
        LingCallContextSnapshot previous = capture();
        if (snapshot == null) {
            LingCallContext.clear();
            return previous;
        }

        if (snapshot.lingId == null && snapshot.labels == null) {
            LingCallContext.clear();
        } else {
            LingCallContext.setLingId(snapshot.lingId);
            LingCallContext.setLabels(snapshot.labels != null ? new HashMap<>(snapshot.labels) : null);
        }

        if (snapshot.traceId != null && !snapshot.traceId.trim().isEmpty()) {
            LingCallContext.setTraceId(snapshot.traceId);
        } else {
            LingCallContext.clearTraceId();
        }

        return previous;
    }

    static void restore(LingCallContextSnapshot previous) {
        if (previous == null) {
            LingCallContext.clear();
            return;
        }

        if (previous.lingId == null && previous.labels == null) {
            LingCallContext.clear();
        } else {
            LingCallContext.setLingId(previous.lingId);
            LingCallContext.setLabels(previous.labels != null ? new HashMap<>(previous.labels) : null);
        }

        if (previous.traceId != null && !previous.traceId.trim().isEmpty()) {
            LingCallContext.setTraceId(previous.traceId);
        } else {
            LingCallContext.clearTraceId();
        }
    }
}
