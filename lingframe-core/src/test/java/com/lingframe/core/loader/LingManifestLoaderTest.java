package com.lingframe.core.loader;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.LingRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LingManifestLoader 测试")
class LingManifestLoaderTest {

    @TempDir
    File tempDir;

    private static final String VALID_YAML = "id: test-ling\nversion: 1.0.0\n";

    @Test
    @DisplayName("从目录解析灵元定义")
    void shouldParseFromDirectory() throws IOException {
        File lingDir = new File(tempDir, "test-ling");
        lingDir.mkdirs();
        Files.write(new File(lingDir, "ling.yml").toPath(), VALID_YAML.getBytes());

        LingDefinition def = LingManifestLoader.parseDefinition(lingDir);

        assertNotNull(def);
        assertEquals("test-ling", def.getId());
        assertEquals("1.0.0", def.getVersion());
    }

    @Test
    @DisplayName("从 Jar 包解析灵元定义")
    void shouldParseFromJar() throws IOException {
        File jarFile = createJarWithLingYml("ling.yml", VALID_YAML);

        LingDefinition def = LingManifestLoader.parseDefinition(jarFile);

        assertNotNull(def);
        assertEquals("test-ling", def.getId());
        assertEquals("1.0.0", def.getVersion());
    }

    @Test
    @DisplayName("目录下无 ling.yml 返回 null")
    void shouldReturnNullWhenNoYmlInDirectory() {
        File emptyDir = new File(tempDir, "empty-ling");
        emptyDir.mkdirs();

        LingDefinition def = LingManifestLoader.parseDefinition(emptyDir);

        assertNull(def);
    }

    @Test
    @DisplayName("Jar 包中无 ling.yml 返回 null")
    void shouldReturnNullWhenNoYmlInJar() throws IOException {
        File jarFile = createJarWithLingYml("other.yml", "key: value");

        LingDefinition def = LingManifestLoader.parseDefinition(jarFile);

        assertNull(def);
    }

    @Test
    @DisplayName("非 Jar 非目录文件返回 null")
    void shouldReturnNullForNonJarNonDirectory() throws IOException {
        File txtFile = new File(tempDir, "readme.txt");
        Files.write(txtFile.toPath(), "hello".getBytes());

        LingDefinition def = LingManifestLoader.parseDefinition(txtFile);

        assertNull(def);
    }

    @Test
    @DisplayName("无效 YAML 内容的目录应抛 LingRuntimeException")
    void shouldThrowForInvalidYamlInDirectory() throws IOException {
        File lingDir = new File(tempDir, "bad-ling");
        lingDir.mkdirs();
        Files.write(new File(lingDir, "ling.yml").toPath(), "{{invalid yaml".getBytes());

        // 🔥 ling.yml 存在但解析失败时必须抛异常，避免坏灵元被静默跳过
        LingRuntimeException ex = assertThrows(LingRuntimeException.class,
                () -> LingManifestLoader.parseDefinition(lingDir));
        assertTrue(ex.getMessage().contains("Invalid ling.yml"));
    }

    @Test
    @DisplayName("损坏的 Jar 文件应抛 LingRuntimeException")
    void shouldThrowForCorruptJar() throws IOException {
        File badJar = new File(tempDir, "corrupt.jar");
        Files.write(badJar.toPath(), "not a jar file".getBytes());

        // 🔥 jar 文件本身损坏（无法打开）时抛异常，让用户感知错误而非静默跳过
        LingRuntimeException ex = assertThrows(LingRuntimeException.class,
                () -> LingManifestLoader.parseDefinition(badJar));
        assertTrue(ex.getMessage().contains("Failed to open jar file"));
    }

    @Test
    @DisplayName("解析包含 canary 属性的定义")
    void shouldParseCanaryProperties() throws IOException {
        String yaml = "id: canary-ling\nversion: 2.0.0\nproperties:\n  canary: true\n";
        File lingDir = new File(tempDir, "canary-ling");
        lingDir.mkdirs();
        Files.write(new File(lingDir, "ling.yml").toPath(), yaml.getBytes());

        LingDefinition def = LingManifestLoader.parseDefinition(lingDir);

        assertNotNull(def);
        assertEquals("canary-ling", def.getId());
        assertNotNull(def.getProperties());
        assertEquals(true, def.getProperties().get("canary"));
    }

    private File createJarWithLingYml(String entryName, String content) throws IOException {
        File jarFile = new File(tempDir, "test.jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile))) {
            JarEntry entry = new JarEntry(entryName);
            jos.putNextEntry(entry);
            jos.write(content.getBytes());
            jos.closeEntry();
        }
        return jarFile;
    }
}
