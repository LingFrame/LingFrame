package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;

import java.io.File;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 面向部署与卸载的顶层生命周期编排入口。
 * <p>
 * 架构边界如下：
 * 该引擎负责把外部的部署/卸载意图翻译为具体的运行时动作与实例动作，
 * 它负责装配与编排，但状态写入仍然委托给专门的协调器。
 */
public interface LingLifecycleEngine {

    /**
     * 在指定灵元的生命周期互斥锁保护下执行复合操作。
     * <p>
     * 适用于需要跨多次 deploy/undeploy 调用保持原子性的编排场景
     * （如灰度提升、热重载等）。单次调用已由引擎内部自动加锁，
     * 无需显式使用本方法。
     * <p>
     * 锁为 ReentrantLock，action 内部调用的 deploy/undeploy/recover
     * 走同一把锁，天然可重入，不会死锁。
     *
     * @param lingId 灵元 ID
     * @param action 需要原子执行的操作
     * @param <T>    返回值类型
     * @return action 的返回值
     */
    default <T> T withLifecycleLock(String lingId, Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 执行一次从制品源到可用实例的完整部署。
     */
    void deploy(LingDefinition lingDefinition, File sourceFile, boolean isDefault,
            Map<String, String> labels);

    /**
     * 面向重载场景的部署路径。
     * 允许同版本短暂并存，以便在旧实例切流前先创建新实例。
     */
    default void deployForReload(LingDefinition lingDefinition, File sourceFile, boolean isDefault,
            Map<String, String> labels) {
        deploy(lingDefinition, sourceFile, isDefault, labels);
    }

    /**
     * 卸载整个灵元运行时，并回收全部关联资源。
     */
    void undeploy(String lingId);

    default LingUninstallResult undeployWithReport(String lingId) {
        undeploy(lingId);
        return LingUninstallResult.triggered(lingId, null, null);
    }

    /**
     * 卸载某个具体版本。
     * 如果它已经是最后一个版本，则连同运行时一起移除。
     */
    void undeploy(String lingId, String version);

    default LingUninstallResult undeployWithReport(String lingId, String version) {
        undeploy(lingId, version);
        return LingUninstallResult.triggered(lingId, version, null);
    }

    /**
     * 卸载某个具体实例对象。
     * 默认实现按版本路由，只有在实现类确实需要实例级特化逻辑时才需要覆盖。
     */
    default void undeploy(String lingId, LingInstance instance) {
        if (instance != null) {
            undeploy(lingId, instance.getVersion());
        }
    }

    /**
     * 触发一次受控恢复。
     * 用于 ERROR / DEGRADED 场景下清理治理态、重试实例启动并收敛回稳定状态。
     */
    default void recover(String lingId) {
        recover(lingId, null);
    }

    default void recover(String lingId, String version) {
        throw new UnsupportedOperationException("Recovery is not supported by current lifecycle engine");
    }
}
