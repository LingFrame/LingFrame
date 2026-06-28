package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.CanaryConfigurable;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingAlertManager;
import com.lingframe.core.spi.LingGovernanceMetricsCollector;
import com.lingframe.core.spi.LingMetricsCollector;

import java.util.Optional;

public interface LingFrameRuntime extends LingLifecycleEngine {

    LingRepository getRepository();

    LingServiceRegistry getServiceRegistry();

    InvocationPipelineEngine getPipelineEngine();

    EventBus getEventBus();

    default Optional<CanaryConfigurable> getCanaryConfigurable() {
        return Optional.empty();
    }

    default Optional<LingMetricsCollector> getMetricsCollector() {
        return Optional.empty();
    }

    default Optional<LingGovernanceMetricsCollector> getGovernanceMetricsCollector() {
        return Optional.empty();
    }

    default Optional<LingAlertManager> getAlertManager() {
        return Optional.empty();
    }

    default Optional<LeakDetector> getLeakDetector() {
        return Optional.empty();
    }
}
