package com.lingframe.core.loader;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.util.YamlCompatUtils;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Slf4j
public class LingManifestLoader {

    private static final String LING_MANIFEST_NAME = "ling.yml";

    /**
     * 解析单元定义 (支持 Jar 和 目录)
     *
     * @param file 单元文件（jar）或目录
     * @return 单元定义，如果不是合法单元则返回 null
     */
    public static LingDefinition parseDefinition(File file) {
        if (file.isDirectory()) {
            return parseFromDirectory(file);
        } else if (file.getName().endsWith(".jar")) {
            return parseFromJar(file);
        }
        return null; // 忽略非 Jar 和非目录的文件
    }

    private static LingDefinition parseFromDirectory(File dir) {
        File ymlFile = new File(dir, LING_MANIFEST_NAME);
        // 如果开发目录下没有 ling.yml，可能是在 resources 下，
        // 这里假设结构是 target/classes/ling.yml 或者 src/main/resources/ling.yml
        // 简化起见，我们优先检查根目录，或者标准的 classpath 根目录
        if (!ymlFile.exists()) {
            // 尝试兼容 Maven 结构：如果是 classes 目录，yml 应该在其中
            // 如果 dir 本身就是 classes 目录，那上面的 check 已经涵盖了
            return null;
        }

        try (InputStream is = Files.newInputStream(ymlFile.toPath())) {
            return load(is);
        } catch (Exception e) {
            log.debug("Found directory {} but failed to parse ling.yml", dir.getName(), e);
            return null;
        }
    }

    private static LingDefinition parseFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(LING_MANIFEST_NAME);
            if (entry == null) {
                log.debug("Skipping jar {}: No {} found inside.", jarFile.getName(), LING_MANIFEST_NAME);
                return null;
            }

            try (InputStream is = jar.getInputStream(entry)) {
                return load(is);
            }
        } catch (Exception e) {
            log.warn("Error reading jar file: {}", jarFile.getName(), e);
            return null;
        }
    }

    private static LingDefinition load(InputStream inputStream) {
        Yaml yaml = YamlCompatUtils.createLoaderYaml();

        // 使用 try-with-resources 确保流正确关闭
        try (InputStream is = inputStream) {
            return yaml.loadAs(is, LingDefinition.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load YAML configuration", e);
        }
    }

}
