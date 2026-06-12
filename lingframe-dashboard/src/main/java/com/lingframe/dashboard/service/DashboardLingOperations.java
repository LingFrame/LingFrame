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
import com.lingframe.core.router.CanaryRouter;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Dashboard 灵元生命周期操作编排器。
 */
public class DashboardLingOperations {

    private final LingLifecycleEngine lifecycleEngine;
    private final LingRepository lingRepository;
    private final CanaryRouter canaryRouter;
    private final DashboardLifecycleEventStore lifecycleEventStore;
    private final DashboardLingSourceResolver lingSourceResolver;

    public DashboardLingOperations(LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            CanaryRouter canaryRouter,
            DashboardLifecycleEventStore lifecycleEventStore,
            DashboardLingSourceResolver lingSourceResolver) {
        this.lifecycleEngine = lifecycleEngine;
        this.lingRepository = lingRepository;
        this.canaryRouter = canaryRouter;
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
            boolean isCanary = lingSourceResolver.isCanary(definition);
            lifecycleEngine.deploy(definition, file, !isCanary, Collections.<String, String>emptyMap());
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
            canaryRouter.removeCanaryConfig(lingId);
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
            canaryRouter.removeCanaryConfig(lingId);
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
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                throw new LingNotFoundException(lingId);
            }

            LingInstance target = version != null
                    ? runtime.getInstancePool().getInstance(version)
                    : lingSourceResolver.selectStableInstance(runtime);
            if (target == null) {
                throw new LingInstallException(lingId, "No available instance to reload", null);
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

            lifecycleEngine.undeploy(lingId, target);
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
