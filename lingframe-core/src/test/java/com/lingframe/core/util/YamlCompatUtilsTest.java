package com.lingframe.core.util;

import com.lingframe.api.config.LingDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("YamlCompatUtils 测试")
class YamlCompatUtilsTest {

    @Test
    @DisplayName("createSafeYaml 应返回可用 Yaml 实例")
    @SuppressWarnings("unchecked")
    void shouldCreateSafeYaml() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();

        assertNotNull(yaml);
        Map<String, Object> result = (Map<String, Object>) yaml.load("key: value");
        assertEquals("value", result.get("key"));
    }

    @Test
    @DisplayName("createLoaderYaml 应返回可用 Yaml 实例")
    @SuppressWarnings("unchecked")
    void shouldCreateLoaderYaml() {
        Yaml yaml = YamlCompatUtils.createLoaderYaml();

        assertNotNull(yaml);
        Map<String, Object> result = (Map<String, Object>) yaml.load("name: test");
        assertEquals("test", result.get("name"));
    }

    @Test
    @DisplayName("createSafeYaml 应能解析复杂 YAML")
    @SuppressWarnings("unchecked")
    void shouldParseComplexYaml() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        String input = "id: test-ling\nversion: 1.0.0\nitems:\n  - a\n  - b\n";

        Map<String, Object> result = (Map<String, Object>) yaml.load(input);

        assertEquals("test-ling", result.get("id"));
        assertEquals("1.0.0", result.get("version"));
        assertNotNull(result.get("items"));
    }

    @Test
    @DisplayName("createLoaderYaml 应能解析 LingDefinition")
    void shouldParseLingDefinition() {
        Yaml yaml = YamlCompatUtils.createLoaderYaml();
        String input = "id: test-ling\nversion: 1.0.0\n";

        LingDefinition def = yaml.loadAs(input, LingDefinition.class);

        assertNotNull(def);
        assertEquals("test-ling", def.getId());
        assertEquals("1.0.0", def.getVersion());
    }

    @Test
    @DisplayName("createSafeYaml 带自定义 DumperOptions")
    @SuppressWarnings("unchecked")
    void shouldCreateSafeYamlWithDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);

        Yaml yaml = YamlCompatUtils.createSafeYaml(options);

        assertNotNull(yaml);
        Map<String, Object> result = (Map<String, Object>) yaml.load("key: value");
        assertEquals("value", result.get("key"));
    }

    @Test
    @DisplayName("createSafeYaml 应能 dump 和 load 数据")
    @SuppressWarnings("unchecked")
    void shouldDumpAndLoad() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        Map<String, Object> data = Collections.singletonMap("name", "test");

        String dumped = yaml.dump(data);
        assertNotNull(dumped);

        Map<String, Object> loaded = (Map<String, Object>) yaml.load(dumped);
        assertEquals("test", loaded.get("name"));
    }

    @Test
    @DisplayName("createSafeYaml 应能解析嵌套 Map")
    @SuppressWarnings("unchecked")
    void shouldParseNestedMap() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        String input = "parent:\n  child: value\n";

        Map<String, Object> result = (Map<String, Object>) yaml.load(input);
        Map<String, Object> parent = (Map<String, Object>) result.get("parent");
        assertEquals("value", parent.get("child"));
    }

    @Test
    @DisplayName("createSafeYaml 应能解析列表")
    @SuppressWarnings("unchecked")
    void shouldParseList() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        String input = "items:\n  - one\n  - two\n  - three\n";

        Map<String, Object> result = (Map<String, Object>) yaml.load(input);
        List<String> items = (List<String>) result.get("items");
        assertEquals(3, items.size());
    }

    @Test
    @DisplayName("createLoaderYaml 应能解析空文档")
    void shouldParseEmptyDocument() {
        Yaml yaml = YamlCompatUtils.createLoaderYaml();
        Object result = yaml.load("");
        assertNull(result);
    }

    @Test
    @DisplayName("createSafeYaml 带默认 DumperOptions dump 多键 Map")
    @SuppressWarnings("unchecked")
    void shouldDumpAndLoadMultiKeyMap() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "test");
        data.put("count", 42);
        data.put("enabled", true);

        String dumped = yaml.dump(data);
        Map<String, Object> loaded = (Map<String, Object>) yaml.load(dumped);
        assertEquals("test", loaded.get("name"));
        assertEquals(42, loaded.get("count"));
        assertEquals(true, loaded.get("enabled"));
    }
}
