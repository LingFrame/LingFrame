package com.lingframe.core.loader;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.LingRuntimeException;
import com.lingframe.core.util.YamlCompatUtils;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Slf4j
public class LingManifestLoader {

    private static final String LING_MANIFEST_NAME = "ling.yml";

    /**
     * 解析灵元定义 (支持 Jar 和 目录)
     * <p>
     * 语义区分：
     * <ul>
     *   <li>非灵元包（无 ling.yml）→ 返回 {@code null}，调用方按"无灵元可加载"处理</li>
     *   <li>ling.yml 存在但解析失败 → 抛 {@link LingRuntimeException}，
     *       调用方必须感知错误，避免坏灵元被静默跳过</li>
     * </ul>
     *
     * @param file 灵元文件（jar）或目录
     * @return 灵元定义，如果不是灵元包则返回 null
     * @throws LingRuntimeException 当 ling.yml 存在但解析失败时抛出
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
            return null; // 非灵元包
        }

        try (InputStream is = Files.newInputStream(ymlFile.toPath())) {
            // 🔥 ling.yml 存在但解析失败时由 load 抛 LingRuntimeException，
            // 不再吞异常返回 null——否则坏灵元会被静默跳过，难以排查
            return load(is, dir.getName());
        } catch (IOException e) {
            // 文件流打开失败（权限/被锁等）属于 IO 错误，统一抛出避免静默
            throw new LingRuntimeException(dir.getName(),
                    "Failed to read ling.yml from directory: " + dir.getName(), e);
        }
    }

    private static LingDefinition parseFromJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(LING_MANIFEST_NAME);
            if (entry == null) {
                log.debug("Skipping jar {}: No {} found inside.", jarFile.getName(), LING_MANIFEST_NAME);
                return null; // 非灵元包
            }

            try (InputStream is = jar.getInputStream(entry)) {
                // 🔥 ling.yml 存在但解析失败时由 load 抛 LingRuntimeException，不再吞
                return load(is, jarFile.getName());
            } catch (IOException e) {
                throw new LingRuntimeException(jarFile.getName(),
                        "Failed to read ling.yml from jar: " + jarFile.getName(), e);
            }
        } catch (IOException e) {
            // JarFile 打开失败（损坏、非 zip 等）：抛异常而非返回 null，
            // 因为这种情况下用户期望看到明确的错误，而非"灵元被跳过"
            throw new LingRuntimeException(jarFile.getName(),
                    "Failed to open jar file: " + jarFile.getName(), e);
        }
    }

    private static LingDefinition load(InputStream inputStream, String sourceName) {
        Yaml yaml = YamlCompatUtils.createLoaderYaml();

        // 使用 try-with-resources 确保流正确关闭
        try (InputStream is = inputStream) {
            return yaml.loadAs(is, LingDefinition.class);
        } catch (IOException e) {
            // 🔥 流读取错误抛 LingRuntimeException，不再用裸 RuntimeException
            throw new LingRuntimeException(sourceName,
                    "Failed to read ling.yml from: " + sourceName, e);
        } catch (YAMLException e) {
            // 🔥 YAML 语法/语义错误显式抛出，不再被外层 catch(Exception) 吞掉
            throw new LingRuntimeException(sourceName,
                    "Invalid ling.yml in " + sourceName + ": " + e.getMessage(), e);
        }
    }

}
