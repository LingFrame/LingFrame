package com.lingframe.core.pipeline;

import com.lingframe.core.model.EngineTrace;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行阶段协议分区。
 * 这里承载“本次要不要真实执行”和“要不要采集轨迹”等执行协议。
 */
@Getter
@Setter
public class InvocationExecutionState {

    /**
     * 当前调用的执行模式。
     */
    private InvocationExecutionMode mode = InvocationExecutionMode.NORMAL;

    /**
     * 调用链路追踪。
     * 只在模拟、审计或诊断场景下有价值；对象池复用时尽量复用 List 本身，避免重复分配。
     */
    private List<EngineTrace> traces;

    public void addTrace(EngineTrace trace) {
        if (trace == null) {
            return;
        }
        if (this.traces == null) {
            this.traces = new ArrayList<>();
        }
        this.traces.add(trace);
    }

    void reset() {
        this.mode = InvocationExecutionMode.NORMAL;
        if (this.traces != null) {
            this.traces.clear();
        }
    }

    void copyFrom(InvocationExecutionState source) {
        if (source == null) {
            return;
        }
        this.mode = source.mode;
        if (source.traces != null && !source.traces.isEmpty()) {
            if (this.traces == null) {
                this.traces = new ArrayList<>();
            }
            this.traces.addAll(source.traces);
        }
    }
}
