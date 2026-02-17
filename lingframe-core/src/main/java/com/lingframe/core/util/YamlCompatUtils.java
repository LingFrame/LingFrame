package com.lingframe.core.util;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * YAML 兼容工具类
 * 处理 SnakeYAML 1.x 和 2.x 的差异，特别是 2.x 的 TagInspector 安全检查。
 */
@Slf4j
public class YamlCompatUtils {

    /**
     * 创建兼容 SnakeYAML 1.x / 2.x 的 Yaml 实例。
     *
     * SnakeYAML 2.x 默认禁止 !! 全局标签（安全加固），
     * 需要通过 TagInspector 显式放行 com.lingframe.* 包下的类型。
     * SnakeYAML 1.x 无此限制，TagInspector 接口也不存在，直接 fallback。
     */
    public static Yaml createSafeYaml() {
        return createSafeYaml(new DumperOptions());
    }

    public static Yaml createSafeYaml(DumperOptions dumperOptions) {
        LoaderOptions loaderOptions = new LoaderOptions();
        configureTagInspector(loaderOptions);

        // 使用默认 Constructor，但配置了 TagInspector
        return new Yaml(new Constructor(loaderOptions), new Representer(dumperOptions), dumperOptions, loaderOptions);
    }

    /**
     * 创建仅用于加载的 Yaml 实例 (使用默认 DumperOptions)
     */
    public static Yaml createLoaderYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        configureTagInspector(loaderOptions);
        return new Yaml(new Constructor(loaderOptions));
    }

    private static void configureTagInspector(LoaderOptions loaderOptions) {
        try {
            // SnakeYAML 2.x: org.yaml.snakeyaml.inspector.TagInspector
            Class<?> tagInspectorClass = Class.forName(
                    "org.yaml.snakeyaml.inspector.TagInspector");

            // 动态代理创建 TagInspector：允许 com.lingframe.* 类型标签
            Object inspector = Proxy.newProxyInstance(
                    YamlCompatUtils.class.getClassLoader(),
                    new Class<?>[] { tagInspectorClass },
                    (proxy, method, args) -> {
                        if ("isGlobalTagAllowed".equals(method.getName())) {
                            // args[0] 是 org.yaml.snakeyaml.nodes.Tag
                            Object tag = args[0];
                            String className = (String) tag.getClass()
                                    .getMethod("getClassName").invoke(tag);
                            return className != null
                                    && className.startsWith("com.lingframe.");
                        }
                        return false;
                    });

            Method setter = LoaderOptions.class.getMethod(
                    "setTagInspector", tagInspectorClass);
            setter.invoke(loaderOptions, inspector);

            // log.debug("SnakeYAML 2.x detected, TagInspector configured");
        } catch (ClassNotFoundException ignored) {
            // SnakeYAML 1.x: TagInspector 不存在，所有标签默认允许
        } catch (Exception e) {
            log.warn("Failed to configure SnakeYAML TagInspector: {}", e.getMessage());
        }
    }
}
