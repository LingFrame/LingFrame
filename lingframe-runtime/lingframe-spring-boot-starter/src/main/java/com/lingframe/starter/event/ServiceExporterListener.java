package com.lingframe.starter.event;

import com.lingframe.api.event.lifecycle.LingStartedEvent;
import com.lingframe.api.event.lifecycle.LingStoppedEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingManager;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.starter.spi.ServiceExporter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 外骨骼服务暴露监听器
 * <p>
 * 监听底层 Ling 单元的启停事件。当 Ling 启动成功时，
 * 获取该单元暴露的服务，派发至 Nacos 等对应的第三方注册中心。
 */
@Slf4j
public class ServiceExporterListener {

    private final LingManager lingManager;
    private final List<ServiceExporter> exporters;

    public ServiceExporterListener(EventBus eventBus, LingManager lingManager, List<ServiceExporter> exporters) {
        this.lingManager = lingManager;
        this.exporters = exporters != null ? exporters : new ArrayList<>();

        if (!this.exporters.isEmpty()) {
            eventBus.subscribe("system", LingStartedEvent.class, this::onLingStarted);
            eventBus.subscribe("system", LingStoppedEvent.class, this::onLingStopped);
            log.info("Registered ServiceExporterListener with {} exporters", this.exporters.size());
        }
    }

    private void onLingStarted(LingStartedEvent event) {
        String lingId = event.getLingId();
        LingRuntime runtime = lingManager.getRuntime(lingId);
        if (runtime == null) {
            return;
        }

        try {
            // TODO V0.3.0: 适配新的 LingServiceRegistry 取代老版 InvokableService
            List<String> services = new ArrayList<>();

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
}
