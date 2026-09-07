package com.lingframe.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiOverrideVerifier 测试。
 * 覆盖：JAR/目录 API 覆盖检测、空输入、无覆盖。
 */
@DisplayName("ApiOverrideVerifier 测试")
class ApiOverrideVerifierTest {

    // ==================== JAR 扫描 ====================

    @Nested
    @DisplayName("JAR 扫描")
    class JarScan {

        @Test
        @DisplayName("空 JAR 无覆盖")
        void emptyJarNoOverrides(@TempDir Path temp) throws IOException {
            File jar = createEmptyJar(temp);
            ApiOverrideVerifier verifier = new ApiOverrideVerifier();
            assertDoesNotThrow(() -> verifier.verify("ling-1", jar));
        }

        @Test
        @DisplayName("null JAR 不抛异常")
        void nullJarSafe() {
            ApiOverrideVerifier verifier = new ApiOverrideVerifier();
            assertDoesNotThrow(() -> verifier.verify("ling-1", (File) null));
        }

        @Test
        @DisplayName("不存在的 JAR 不抛异常")
        void nonExistentJarSafe() {
            ApiOverrideVerifier verifier = new ApiOverrideVerifier();
            assertDoesNotThrow(() -> verifier.verify("ling-1", new File("nonexistent.jar")));
        }
    }

    // ==================== 目录扫描 ====================

    @Nested
    @DisplayName("目录扫描")
    class DirectoryScan {

        @Test
        @DisplayName("空目录无覆盖")
        void emptyDirNoOverrides(@TempDir Path temp) throws IOException {
            ApiOverrideVerifier verifier = new ApiOverrideVerifier();
            assertDoesNotThrow(() -> verifier.verify("ling-1", temp.toFile()));
        }

        @Test
        @DisplayName("null 目录不抛异常")
        void nullDirSafe() {
            ApiOverrideVerifier verifier = new ApiOverrideVerifier();
            assertDoesNotThrow(() -> verifier.verify("ling-1", (File) null));
        }
    }

    // ==================== 辅助方法 ====================

    private File createEmptyJar(Path temp) throws IOException {
        File jar = temp.resolve("empty.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            // 空 JAR
        }
        return jar;
    }
}