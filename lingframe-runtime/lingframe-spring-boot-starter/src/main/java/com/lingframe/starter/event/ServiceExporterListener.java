package com.lingframe.starter.event;

import com.lingframe.api.event.lifecycle.LingStartedEvent;
import com.lingframe.api.event.lifecycle.LingStoppedEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
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

    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;
    private final List<ServiceExporter> exporters;

    public ServiceExporterListener(EventBus eventBus, LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry, List<ServiceExporter> exporters) {
        this.lingRepository = lingRepository;
        this.lingServiceRegistry = lingServiceRegistry;
        this.exporters = exporters != null ? exporters : new ArrayList<>();

        if (!this.exporters.isEmpty()) {
            eventBus.subscribe("system", LingStartedEvent.class, this::onLingStarted);
            eventBus.subscribe("system", LingStoppedEvent.class, this::onLingStopped);
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
}
