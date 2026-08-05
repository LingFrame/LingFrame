package com.lingframe.dashboard.service;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.routing.MigrationStateHolder;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Dashboard 灵元生命周期操作编排器。
 */
public class DashboardLingOperations {

    private final LingLifecycleEngine lifecycleEngine;
    private final LingRepository lingRepository;
    private final MigrationStateHolder migrationStateHolder;
    private final DashboardLifecycleEventStore lifecycleEventStore;
    private final DashboardLingSourceResolver lingSourceResolver;

    // 每个 lingId 独立的重载锁，防止并发 reload 导致版本号竞态和实例残留
    private final Map<String, ReentrantLock> reloadLocks = new ConcurrentHashMap<>();

    public DashboardLingOperations(LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            MigrationStateHolder migrationStateHolder,
            DashboardLifecycleEventStore lifecycleEventStore,
            DashboardLingSourceResolver lingSourceResolver) {
        this.lifecycleEngine = lifecycleEngine;
        this.lingRepository = lingRepository;
        this.migrationStateHolder = migrationStateHolder;
        this.lifecycleEventStore = lifecycleEventStore;
        this.lingSourceResolver = lingSourceResolver;
    }

    public DashboardLingSourceResolver getLingSourceResolver() {
        return this.lingSourceResolver;
    }


    public String installLing(File file) {
        try {
            LingDefinition definition = LingManifestLoader.parseDefinition(file);
            if (definition == null) {
                throw new InvalidArgumentException("file", "Not a valid ling package: " + file.getName());
            }
            LingRuntime runtime = lingRepository.getRuntime(definition.getId());
            boolean setAsDefault = runtime == null || runtime.getInstancePool().getDefault() == null;
            lifecycleEngine.deploy(definition, file, setAsDefault, Collections.<String, String>emptyMap());
            lifecycleEventStore.addEvent(
                    definition.getId(),
                    definition.getVersion(),
                    "READY",
                    "灵元安装完成",
                    "灵元 " + definition.getId() + " 版本 " + definition.getVersion() + " 安装成功并准备就绪");
            return definition.getId();
        } catch (Exception e) {
            throw new LingInstallException("unknown", "Failed to install ling: " + e.getMessage(), e);
        }
    }

    public LingUninstallResult uninstallLing(String lingId) {
        try {
            if (migrationStateHolder != null) {
                migrationStateHolder.evict(lingId);
            }
            LingUninstallResult result = lifecycleEngine.undeployWithReport(lingId);
            if (result.isUninstallTriggered()) {
                lifecycleEventStore.addEvent(
                        lingId,
                        "",
                        "DEAD",
                        "灵元完全卸载",
                        "灵元 " + lingId + " 已完成卸载，所有版本均已移除");
            }
            return result;
        } catch (Exception e) {
            throw new LingInstallException(lingId, "Failed to uninstall ling: " + e.getMessage(), e);
        }
    }

    public LingUninstallResult uninstallLing(String lingId, String version) {
        try {
            if (migrationStateHolder != null) {
                migrationStateHolder.evict(lingId);
            }
            LingUninstallResult result = lifecycleEngine.undeployWithReport(lingId, version);
            if (result.isUninstallTriggered()) {
                lifecycleEventStore.addEvent(
                        lingId,
                        version,
                        "UNLOAD",
                        "灵元版本卸载",
                        "灵元 " + lingId + " 版本 " + version + " 已卸载");
            }
            return result;
        } catch (Exception e) {
            throw new LingInstallException(
                    lingId,
                    "Failed to uninstall ling version " + version + ": " + e.getMessage(),
                    e);
        }
    }

    public String reloadLing(String lingId, String version) {
        // 同一 lingId 的 reload 必须串行执行，避免：
        // 1. buildReloadVersion 并发竞态生成重复版本号
        // 2. target 实例在 deployForReload 期间被其他 reload tearDown，导致 undeploy 失败
        ReentrantLock lock = reloadLocks.computeIfAbsent(lingId, k -> new ReentrantLock());
        lock.lock();
        try {
            return doReloadLing(lingId, version);
        } finally {
            lock.unlock();
        }
    }

    private String doReloadLing(String lingId, String version) {
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                throw new LingNotFoundException(lingId);
            }

            LingInstance target = version != null
                    ? runtime.getInstancePool().getInstance(version)
                    : lingSourceResolver.selectStableInstance(runtime);
            if (target == null) {
                String message = version != null
                        ? "版本 " + version + " 不存在或已被卸载"
                        : "无可用实例可重载";
                throw new LingInstallException(lingId, message, null);
            }

            String targetVersion = target.getVersion();
            String baseVersion = targetVersion;
            int reloadIdx = targetVersion.indexOf("-reload-");
            if (reloadIdx > 0) {
                baseVersion = targetVersion.substring(0, reloadIdx);
            }

            File source = lingSourceResolver.resolveSourceFile(lingId, baseVersion);
            if (source == null) {
                throw new LingInstallException(lingId,
                        "Source file not found for " + lingId + ":" + targetVersion, null);
            }

            LingDefinition definition = LingManifestLoader.parseDefinition(source);
            if (definition == null) {
                throw new LingInstallException(lingId, "Invalid ling package: " + source.getAbsolutePath(), null);
            }

            boolean wasDefault = runtime.getInstancePool().getDefault() == target;
            Map<String, String> labels = new HashMap<String, String>(target.getLabels());
            String reloadVersion = lingSourceResolver.buildReloadVersion(runtime, baseVersion);
            definition.setVersion(reloadVersion);
            lingSourceResolver.markReload(definition, labels, reloadVersion);

            lifecycleEngine.deployForReload(definition, source, wasDefault, labels);

            LingInstance newInstance = runtime.getInstancePool().getInstance(reloadVersion);
            if (newInstance == null) {
                throw new LingInstallException(lingId, "Hot reload failed: new instance not found", null);
            }

            // 仅当旧实例仍被实例池持有时才显式卸载：
            // wasDefault=true 且旧实例空闲时,deployForReload 内部的 publishReadyInstance 已将其
            // 移入濒死队列并回收（def 置空），此时再 undeploy(target) 会命中 engine 的
            // getAllInstances().contains 守卫，打印误导性的 <destroyed> WARN；
            // 若旧实例仍在池中（非默认实例，或替换时尚有在途请求未被立即回收），
            // 则必须显式卸载以确保资源回收。
            if (runtime.getInstancePool().getAllInstances().contains(target)) {
                lifecycleEngine.undeploy(lingId, target);
            }
            lifecycleEventStore.addEvent(
                    lingId,
                    reloadVersion,
                    "RELOAD",
                    "灵元热重载",
                    "灵元 " + lingId + " 版本 " + targetVersion + " 已热重载为 " + reloadVersion);
            return lingId;
        } catch (Exception e) {
            throw new LingInstallException(lingId, "Failed to reload ling: " + e.getMessage(), e);
        }
    }
}
