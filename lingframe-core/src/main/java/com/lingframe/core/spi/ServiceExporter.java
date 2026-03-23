package com.lingframe.core.spi;

import java.util.List;

/**
 * SPI: 轻量级的服务中心导出器。
 * <p>
 * 当触发 {@link com.lingframe.api.event.lifecycle.LingStartedEvent} 或在生命周期扫描结束后，
 * 可将 Ling 中的 {@code @LingService} 元数据异步推送给例如 Nacos、注册中心等地。
 */
public interface ServiceExporter {

    /**
     * @param lingId   产生该服务的灵元 ID
     * @param services 从容器扫描到的该灵元的导出服务句柄元数据
     */
    void export(String lingId, List<String> services);

    /**
     * @param lingId 撤销该灵元在外部注册中心的所有发布信息
     */
    void unexport(String lingId);
}
