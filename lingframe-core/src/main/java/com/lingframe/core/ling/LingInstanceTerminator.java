package com.lingframe.core.ling;

import com.lingframe.core.spi.LingContainer;
import lombok.extern.slf4j.Slf4j;

/**
 * 仅负责实例资源终止与强引用拆除，不负责实例状态迁移。
 */
@Slf4j
class LingInstanceTerminator {

    public void terminate(LingInstance instance) {
        if (instance == null) {
            return;
        }

        String lingId = instance.getLingId();
        String version = instance.getVersion();
        LingContainer container = instance.getContainer();

        // stop() 必须"幂等必达"：不依赖 isActive() 判定。
        // 之前用 container.isActive() 短路，一旦容器被提前 stop 过（stopped 标志置位，
        // 如部署失败清理 cleanupOnFailure）或 context 提前非 active，正式卸载时 stop() 被跳过，
        // SpringEcosystemUnloadHook.preCleanup / context.close 等卸载 hooks 静默缺失，
        // 导致类加载器回收不完整（CGLIB 缓存/线程引用残留）——"有时完整有时不完整"的不稳定根因。
        // stop() 自身 CAS 幂等 + context 判空，重复/非 active 调用是安全的。
        if (container != null) {
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
