package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.util.YamlCompatUtils;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
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
    public void updatePatch(String lingId, GovernancePolicy policy) {
        patchMap.put(lingId, policy);
        save();
        log.info("[LingFrame] Governance patch updated for ling: {}", lingId);
        // 通知机制留空，SmartServiceProxy 会实时读取
        // eventBus.publish(new GovernancePatchUpdatedEvent(lingId, policy));
    }

    public GovernancePolicy getPatch(String lingId) {
        return patchMap.get(lingId);
    }

    public Map<String, GovernancePolicy> getAllPatches() {
        return patchMap;
    }

    @SuppressWarnings("unchecked")
    private void load() {
        File file = new File(storePath);
        if (!file.exists())
            return;

        // 显式指定 UTF-8 编码，避免依赖平台默认编码
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            Yaml yaml = YamlCompatUtils.createLoaderYaml();
            Map<String, GovernancePolicy> loaded = yaml.loadAs(reader, Map.class);
            if (loaded != null) {
                patchMap.putAll(loaded);
            }
        } catch (Exception e) {
            log.error("Failed to load governance patches", e);
        }
    }

    private void save() {
        Path target = Paths.get(storePath);
        Path parent = target.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                log.error("Failed to create parent directory for governance patches: {}", parent, e);
                return;
            }
        }
        Path tmp = Paths.get(storePath + ".tmp");

        // 原子写入：先写临时文件，再原子 move 覆盖目标文件
        // 避免 write 期间其他线程读到半截 YAML 导致解析失败
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(tmp.toFile()), StandardCharsets.UTF_8)) {
            DumperOptions options = new DumperOptions();
            options.setIndent(2);
            options.setPrettyFlow(true);
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

            Yaml yaml = YamlCompatUtils.createSafeYaml(options);
            yaml.dump(new HashMap<>(patchMap), writer);
        } catch (IOException e) {
            log.error("Failed to save governance patches", e);
            return;
        }

        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to atomically move governance patch file", e);
            // 清理残留临时文件，避免磁盘泄漏
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException deleteEx) {
                log.warn("Failed to delete temporary governance patch file: {}", tmp, deleteEx);
            }
        }
    }
}
