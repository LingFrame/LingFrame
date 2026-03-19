package com.lingframe.core.ling;

import com.lingframe.core.spi.LingContainer;
import lombok.extern.slf4j.Slf4j;

/**
 * 仅负责实例资源终止与强引用拆除，不负责实例状态迁移。
 */
@Slf4j
final class LingInstanceTerminator {

    public void terminate(LingInstance instance) {
        if (instance == null) {
            return;
        }

        String lingId = instance.getLingId();
        String version = instance.getVersion();
        LingContainer container = instance.getContainer();

        if (container != null && container.isActive()) {
            try {
                container.stop();
                log.info("Ling instance [{}:{}] resources stopped", lingId, version);
            } catch (Exception e) {
                log.error("Error stopping ling instance [{}:{}]", lingId, version, e);
            }
        }

        instance.clearDetachedState();
    }
}
