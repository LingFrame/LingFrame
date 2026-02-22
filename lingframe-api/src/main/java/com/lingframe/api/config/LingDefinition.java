package com.lingframe.api.config;

import com.lingframe.api.exception.InvalidArgumentException;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 对应 ling.yml 的根节点
 * 作为标准契约
 */
@Getter
@Setter
public class LingDefinition implements Serializable {

    // === 基础元数据 ===
    private String id;
    private String version;
    private String provider;
    private String description;

    // === 运行时配置 ===
    private String mainClass; // 单元入口类全限定名

    // === 治理配置 ===
    private GovernancePolicy governance = new GovernancePolicy();

    // === 扩展配置 (KV 键值对，用于业务参数) ===
    private Map<String, Object> properties = new HashMap<>();

    /**
     * 深拷贝
     */
    public LingDefinition copy() {
        LingDefinition copy = new LingDefinition();
        copy.id = this.id;
        copy.version = this.version;
        copy.provider = this.provider;
        copy.description = this.description;
        copy.mainClass = this.mainClass;

        if (this.governance != null) {
            copy.governance = this.governance.copy();
        }

        if (this.properties != null) {
            copy.properties = new HashMap<>(this.properties);
        }

        return copy;
    }

    /**
     * 验证
     */
    public void validate() {
        if (id == null || id.trim().isEmpty()) {
            throw new InvalidArgumentException("id", "Ling id cannot be blank");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new InvalidArgumentException("version", "Ling version cannot be blank");
        }
    }

    @Override
    public String toString() {
        return String.format("LingDefinition{id='%s', version='%s'}", id, version);
    }
}
