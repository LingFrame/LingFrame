package com.lingframe.core.ling;

import com.lingframe.core.alert.AlertManager;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.CanaryConfigurable;
import com.lingframe.core.spi.LeakDetector;

import java.util.Optional;

public interface LingFrameRuntime extends LingLifecycleEngine {

    LingRepository getRepository();

    LingServiceRegistry getServiceRegistry();

    InvocationPipelineEngine getPipelineEngine();

    EventBus getEventBus();

    default Optional<CanaryConfigurable> getCanaryConfigurable() {
        return Optional.empty();
    }

    default Optional<MetricsCollector> getMetricsCollector() {
        return Optional.empty();
    }

    default Optional<GovernanceMetricsCollector> getGovernanceMetricsCollector() {
        return Optional.empty();
    }

    default Optional<AlertManager> getAlertManager() {
        return Optional.empty();
    }

    default Optional<LeakDetector> getLeakDetector() {
        return Optional.empty();
    }
}
