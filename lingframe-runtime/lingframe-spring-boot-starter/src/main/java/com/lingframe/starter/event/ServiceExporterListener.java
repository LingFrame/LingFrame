package com.lingframe.starter.event;

import com.lingframe.api.event.lifecycle.LingStartedEvent;
import com.lingframe.api.event.lifecycle.LingStoppedEvent;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.spi.ServiceExporter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 外骨骼服务暴露监听器
 * <p>
 * 监听底层 Ling 灵元的启停事件。当 Ling 启动成功时，
 * 获取该灵元暴露的服务，派发至 Nacos 等对应的第三方注册中心。
 */
@Slf4j
public class ServiceExporterListener {

    private final EventBus eventBus;
    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;
    private final List<ServiceExporter> exporters;
    private final LingEventListener<LingStartedEvent> startedListener;
    private final LingEventListener<LingStoppedEvent> stoppedListener;

    public ServiceExporterListener(EventBus eventBus, LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry, List<ServiceExporter> exporters) {
        this.eventBus = eventBus;
        this.lingRepository = lingRepository;
        this.lingServiceRegistry = lingServiceRegistry;
        this.exporters = exporters != null ? exporters : new ArrayList<>();
        this.startedListener = this::onLingStarted;
        this.stoppedListener = this::onLingStopped;

        if (!this.exporters.isEmpty()) {
            eventBus.subscribe("system", LingStartedEvent.class, startedListener);
            eventBus.subscribe("system", LingStoppedEvent.class, stoppedListener);
            log.info("Registered ServiceExporterListener with {} exporters", this.exporters.size());
        }
    }

    private void onLingStarted(LingStartedEvent event) {
        String lingId = event.getLingId();
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return;
        }

        try {
            // 这里使用新的 LingServiceRegistry 获取服务列表
            List<String> services = lingServiceRegistry.getServicesByLingId(lingId);

            if (exporters != null && !exporters.isEmpty()) {
                exporters.forEach(exporter -> {
                    try {
                        exporter.export(lingId, services);
                    } catch (Exception e) {
                        log.error("Failed to export services for ling [{}] to exporter [{}]", lingId,
                                exporter.getClass().getName(), e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to collect services for export from ling [{}]", lingId, e);
        }
    }

    private void onLingStopped(LingStoppedEvent event) {
        String lingId = event.getLingId();
        exporters.forEach(exporter -> {
            try {
                exporter.unexport(lingId);
            } catch (Exception e) {
                log.error("Failed to unexport services for ling [{}] from exporter [{}]", lingId,
                        exporter.getClass().getName(), e);
            }
        });
    }

    public void shutdown() {
        if (eventBus == null || exporters.isEmpty()) {
            return;
        }
        eventBus.unsubscribe("system", LingStartedEvent.class, startedListener);
        eventBus.unsubscribe("system", LingStoppedEvent.class, stoppedListener);
    }
}
