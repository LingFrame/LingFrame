package com.lingframe.core.metrics;

import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;

import java.util.List;

/**
 * 健康状态 FSM 兜底解析器。
 *
 * <p>当 {@link MetricsSnapshot.HealthStatus} 为 UNKNOWN（指标窗口尚未滑入有效数据）时，
 * 基于实例池 FSM 状态进行兜底推导，消除"部署成功但健康状态为 UNKNOWN"的体验断点。
 *
 * <p>定位为纯函数：无状态、无外部依赖，"查询 repository"的职责交由调用方完成，
 * 本类仅针对入参做确定性推导，便于复用与单测。
 */
public final class HealthStatusResolver {

    private HealthStatusResolver() {
    }

    /**
     * 解析灵元健康状态，优先取指标快照结果，UNKNOWN 时兜底走实例池 FSM。
     *
     * @param metricStatus 指标快照中的健康状态（可能为 null 或 UNKNOWN）
     * @param runtime      灵元运行时（可能为 null，如未部署或已卸载）
     * @return 健康状态字符串：HEALTHY / UNHEALTHY / UNKNOWN
     */
    public static String resolve(MetricsSnapshot.HealthStatus metricStatus, LingRuntime runtime) {
        // 指标已有明确结论（含 WARNING）时直接采用，无需 FSM 兜底
        if (metricStatus != null && metricStatus != MetricsSnapshot.HealthStatus.UNKNOWN) {
            return metricStatus.name();
        }
        if (runtime == null) {
            return "UNKNOWN";
        }
        // 实例池未初始化时按未知处理（与其余防御粒度保持一致）
        if (runtime.getInstancePool() == null) {
            return "UNKNOWN";
        }
        List<LingInstance> instances = runtime.getInstancePool().getActiveInstances();
        if (instances == null || instances.isEmpty()) {
            return "UNKNOWN";
        }
        boolean anyReady = false;
        for (LingInstance instance : instances) {
            // 任一实例已销毁/垂死/FSM 进入 DEAD 或 ERROR：整体判不健康
            if (instance.isDestroyed() || instance.isDying()
                    || instance.currentStatus() == InstanceStatus.DEAD
                    || instance.currentStatus() == InstanceStatus.ERROR) {
                return "UNHEALTHY";
            }
            if (instance.isReady()) {
                anyReady = true;
            }
        }
        // 有就绪实例即探活兜底为 HEALTHY；全部处于过渡态（CREATED/LOADING 等）则保持 UNKNOWN 最诚实
        return anyReady ? "HEALTHY" : "UNKNOWN";
    }
}
