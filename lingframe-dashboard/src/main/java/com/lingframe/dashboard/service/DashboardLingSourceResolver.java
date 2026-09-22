package com.lingframe.dashboard.service;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.util.PathUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 灵元源定位与热重载辅助器。
 */
public class DashboardLingSourceResolver {

    private final LingFrameConfig lingFrameConfig;

    public DashboardLingSourceResolver(LingFrameConfig lingFrameConfig) {
        this.lingFrameConfig = lingFrameConfig;
    }

    /**
     * 列出磁盘 ling-home 目录下所有的 JAR 包物理文件。
     */
    public List<File> listHomeFiles() {
        if (lingFrameConfig == null || lingFrameConfig.getLingHome() == null) {
            return Collections.emptyList();
        }
        File home = new File(lingFrameConfig.getLingHome());
        if (!home.exists() || !home.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = home.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<File>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                list.add(file);
            }
        }
        return list;
    }

    public LingFrameConfig getLingFrameConfig() {
        return this.lingFrameConfig;
    }


    public LingInstance selectStableInstance(LingRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        LingInstance defaultInstance = runtime.getInstancePool().getDefault();
        if (defaultInstance != null) {
            return defaultInstance;
        }
        List<LingInstance> activeInstances = runtime.getInstancePool().getActiveInstances();
        return activeInstances.isEmpty() ? null : activeInstances.get(0);
    }

    public File resolveSourceFile(String lingId, String version) {
        File developmentFile = findFromRoots(lingId, version);
        if (developmentFile != null) {
            return developmentFile;
        }
        return findFromHome(lingId, version);
    }

    /**
     * 解析 ling-home 下的真实物理包（JAR）文件。
     * <p>
     * 物理删除只能作用于 ling-home 中的打包产物，禁止落入 dev 模式的
     * {@code target/classes} 目录——否则删除的是编译后的 class 文件。
     */
    public File resolveHomePackageFile(String lingId, String version) {
        return findFromHome(lingId, version);
    }

    public String buildReloadVersion(LingRuntime runtime, String baseVersion) {
        int max = 0;
        String prefix = baseVersion + "-reload-";
        for (LingInstance instance : runtime.getInstancePool().getAllInstances()) {
            String candidate = instance.getVersion();
            if (candidate != null && candidate.startsWith(prefix)) {
                String suffix = candidate.substring(prefix.length());
                try {
                    int current = Integer.parseInt(suffix);
                    if (current > max) {
                        max = current;
                    }
                } catch (NumberFormatException ignore) {
                    // 忽略历史上格式不正确的重载版本号
                }
            }
        }
        return prefix + (max + 1);
    }

    public void markReload(LingDefinition definition, Map<String, String> labels, String reloadVersion) {
        if (labels != null) {
            labels.put("reload", "true");
            labels.put("reloadVersion", reloadVersion);
        }
        Map<String, Object> properties = definition.getProperties();
        if (properties == null) {
            properties = new HashMap<String, Object>();
            definition.setProperties(properties);
        }
        properties.put("reload", true);
        properties.put("reloadVersion", reloadVersion);
    }

    private File findFromRoots(String lingId, String version) {
        if (lingFrameConfig == null || !lingFrameConfig.isDevMode()) {
            return null;
        }
        List<String> roots = lingFrameConfig.getLingRoots();
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        for (String root : roots) {
            File rootDir = PathUtils.resolvePath(
                    root,
                    lingFrameConfig.getLingHome() != null ? new File(lingFrameConfig.getLingHome()) : null
            );
            if (rootDir == null) {
                continue;
            }
            // dev-mode：探测 Maven 模块构建产物 classes 目录。
            // 双栈产物目录隔离：默认 target/classes，spring-boot3 profile 为 target-boot3/classes
            // （见 lingframe-dependencies spring-boot3 profile 的 bc3.base.build.dir）。
            File candidate = PathUtils.resolveMavenClassesDir(rootDir);
            if (!candidate.exists()) {
                continue;
            }
            LingDefinition definition = LingManifestLoader.parseDefinition(candidate);
            if (definition != null
                    && lingId.equals(definition.getId())
                    && version.equals(definition.getVersion())) {
                return candidate;
            }
        }
        return null;
    }

    private File findFromHome(String lingId, String version) {
        if (lingFrameConfig == null || lingFrameConfig.getLingHome() == null) {
            return null;
        }
        File home = new File(lingFrameConfig.getLingHome());
        if (!home.exists() || !home.isDirectory()) {
            return null;
        }
        File[] files = home.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            LingDefinition definition = LingManifestLoader.parseDefinition(file);
            if (definition != null
                    && lingId.equals(definition.getId())
                    && version.equals(definition.getVersion())) {
                return file;
            }
        }
        return null;
    }
}
