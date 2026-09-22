package com.lingframe.core.util;

import com.lingframe.api.config.LingDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link YamlCompatUtils} 的第二轮补充测试。
 * <p>
 * 已有 {@link YamlCompatUtilsSupplementTest} 覆盖基础路径，此处重点覆盖
 * TagInspector 动态代理的 true/false 分支（通过 !! 全局标签触发）。
 */
@DisplayName("YamlCompatUtils 补充测试 II（TagInspector 分支）")
class YamlCompatUtilsSupplement2Test {

    @Test
    @DisplayName("!! 标签加载 com.lingframe.* 类型应被 TagInspector 放行")
    void shouldAllowLingframeGlobalTag() {
        Yaml yaml = YamlCompatUtils.createLoaderYaml();
        // 用全局标签显式指定类型，触发 TagInspector.isGlobalTagAllowed
        // 类名以 com.lingframe. 开头，代理应返回 true
        String input = "!!com.lingframe.api.config.LingDefinition\nid: tag-test\nversion: 1.0.0\n";
        LingDefinition def =
                yaml.load(input);
        assertNotNull(def);
        assertEquals("tag-test", def.getId());
        assertEquals("1.0.0", def.getVersion());
    }

    @Test
    @DisplayName("!! 标签加载不存在的非 com.lingframe 类应抛异常（TagInspector 拒绝或类加载失败）")
    void shouldRejectNonLingframeGlobalTag() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        // com.example.NonExistent 不存在且不在 com.lingframe 包下
        // TagInspector 应返回 false，或类加载失败——两种情况均抛异常
        String input = "!!com.example.NonExistent\nvalue: test";
        assertThrows(Exception.class, () -> yaml.load(input));
    }

    @Test
    @DisplayName("!! 标签加载 com.lingframe.* 类型到 SafeYaml 也应放行")
    void shouldAllowLingframeTagInSafeYaml() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        String input = "!!com.lingframe.api.config.LingDefinition\nid: safe-tag\nversion: 2.0\n";
        LingDefinition def = yaml.load(input);
        assertNotNull(def);
        assertEquals("safe-tag", def.getId());
    }

    @Test
    @DisplayName("createSafeYaml(DumperOptions) FlowStyle.FLOW 应产出花括号")
    void shouldProduceFlowStyleWithDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        Yaml yaml = YamlCompatUtils.createSafeYaml(options);
        String dumped = yaml.dump(Collections.singletonMap("k", "v"));
        assertNotNull(dumped);
        // FLOW 风格应含花括号
        assertTrue(dumped.contains("{"));
    }

    @Test
    @DisplayName("createLoaderYaml 应能解析嵌套 Map 结构")
    void shouldParseNestedMap() {
        Yaml yaml = YamlCompatUtils.createLoaderYaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) yaml.load(
                "outer:\n  inner: value\n  num: 42\n");
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) result.get("outer");
        assertEquals("value", inner.get("inner"));
        assertEquals(42, inner.get("num"));
    }

    @Test
    @DisplayName("createSafeYaml 应能 dump 并重新 load")
    void shouldRoundTripDumpAndLoad() {
        Yaml yaml = YamlCompatUtils.createSafeYaml();
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "round-trip");
        original.put("value", 100);
        String dumped = yaml.dump(original);
        assertNotNull(dumped);
        @SuppressWarnings("unchecked")
        Map<String, Object> loaded = (Map<String, Object>) yaml.load(dumped);
        assertEquals("round-trip", loaded.get("name"));
        assertEquals(100, loaded.get("value"));
    }
}
