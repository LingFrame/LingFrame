package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.event.EventBus;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地治理注册表 (Core层)
 * 职责：管理动态补丁，支持持久化到本地文件，不依赖数据库
 */
@Slf4j
public class LocalGovernanceRegistry {
    private final Map<String, GovernancePolicy> patchMap = new ConcurrentHashMap<>();
    private final String storePath;
    private final EventBus eventBus;

    public LocalGovernanceRegistry(EventBus eventBus) {
        this(eventBus, "./config/ling-governance-patch.yml");
    }

    public LocalGovernanceRegistry(EventBus eventBus, String storePath) {
        this.eventBus = eventBus;
        this.storePath = storePath;
        load();
    }

    /**
     * 更新动态补丁 (由 Runtime 层的 Controller 调用)
     */
    public void updatePatch(String pluginId, GovernancePolicy policy) {
        patchMap.put(pluginId, policy);
        save();
        log.info("[LingFrame] Governance patch updated for plugin: {}", pluginId);
        // 通知机制留空，SmartServiceProxy 会实时读取
        // eventBus.publish(new GovernancePatchUpdatedEvent(pluginId, policy));
    }

    public GovernancePolicy getPatch(String pluginId) {
        return patchMap.get(pluginId);
    }

    public Map<String, GovernancePolicy> getAllPatches() {
        return patchMap;
    }

    private void load() {
        File file = new File(storePath);
        if (!file.exists())
            return;

        try (FileReader reader = new FileReader(file)) {
            LoaderOptions options = new LoaderOptions();
            // 允许 com.lingframe 包下的 Tag (修复 Global tag is not allowed 错误)
            options.setTagInspector(tag -> tag.getClassName().startsWith("com.lingframe"));
            Yaml yaml = new Yaml(new Constructor(options));
            Map<String, GovernancePolicy> loaded = yaml.load(reader);
            if (loaded != null) {
                patchMap.putAll(loaded);
            }
        } catch (Exception e) {
            log.error("Failed to load governance patches", e);
        }
    }

    private void save() {
        File file = new File(storePath);
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        try (FileWriter writer = new FileWriter(file)) {
            DumperOptions options = new DumperOptions();
            options.setIndent(2);
            options.setPrettyFlow(true);
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

            // 使用 SnakeYAML 序列化 Map
            Yaml yaml = new Yaml(new Representer(new DumperOptions()), options);
            yaml.dump(patchMap, writer);
        } catch (IOException e) {
            log.error("Failed to save governance patches", e);
        }
    }
}
