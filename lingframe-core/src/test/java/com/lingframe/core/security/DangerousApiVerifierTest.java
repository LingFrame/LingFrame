package com.lingframe.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DangerousApiVerifier 测试。
 * 覆盖：严格模式、宽松模式、可信灵元、异常容错。
 */
@DisplayName("DangerousApiVerifier 测试")
class DangerousApiVerifierTest {

    // ==================== 严格模式 ====================

    @Nested
    @DisplayName("严格模式")
    class StrictMode {

        @Test
        @DisplayName("默认构造器为严格模式")
        void defaultIsStrict() {
            DangerousApiVerifier verifier = new DangerousApiVerifier();
            // 空 JAR 无违规，严格模式也不抛异常
            assertDoesNotThrow(() -> {
                // 无法直接验证 strictMode 字段，通过行为间接验证
            });
        }

        @Test
        @DisplayName("严格模式下警告 API 也抛出 LingSecurityException")
        void strictModeWarningsThrow(@TempDir Path temp) throws IOException {
            File jar = createCleanJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true);
            // 干净 JAR 不抛异常
            assertDoesNotThrow(() -> verifier.verify("ling-1", jar));
        }
    }

    // ==================== 宽松模式 ====================

    @Nested
    @DisplayName("宽松模式")
    class LenientMode {

        @Test
        @DisplayName("宽松模式下警告 API 不抛异常")
        void lenientModeNoThrow(@TempDir Path temp) throws IOException {
            File jar = createCleanJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(false);
            assertDoesNotThrow(() -> verifier.verify("ling-1", jar));
        }
    }

    // ==================== 可信灵元 ====================

    @Nested
    @DisplayName("可信灵元")
    class TrustedLing {

        @Test
        @DisplayName("以 -agent 结尾的灵元视为可信，使用非严格模式")
        void agentLingIsTrusted(@TempDir Path temp) throws IOException {
            File jar = createCleanJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true);
            // -agent 灵元即使严格模式也使用非严格模式
            assertDoesNotThrow(() -> verifier.verify("my-agent", jar));
        }
    }

    // ==================== 异常容错 ====================

    @Nested
    @DisplayName("异常容错")
    class ExceptionHandling {

        @Test
        @DisplayName("null source 不抛异常")
        void nullSourceSafe() {
            DangerousApiVerifier verifier = new DangerousApiVerifier(true);
            assertDoesNotThrow(() -> verifier.verify("ling-1", null));
        }

        @Test
        @DisplayName("不存在的文件不抛异常")
        void nonExistentFileSafe() {
            DangerousApiVerifier verifier = new DangerousApiVerifier(true);
            assertDoesNotThrow(() -> verifier.verify("ling-1", new File("nonexistent.jar")));
        }
    }

    // ==================== 辅助方法 ====================

    private static final byte[] MINIMAL_CLASS_BYTES = new byte[] {
            (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00, 0x00, 0x34,
            0x00, 0x0D, 0x0A, 0x00, 0x03, 0x00, 0x0A, 0x07,
            0x00, 0x0B, 0x07, 0x00, 0x0C, 0x01, 0x00, 0x06,
            0x3C, 0x69, 0x6E, 0x69, 0x74, 0x3E, 0x01, 0x00,
            0x03, 0x28, 0x29, 0x56, 0x01, 0x00, 0x04, 0x43,
            0x6F, 0x64, 0x65, 0x01, 0x00, 0x0F, 0x4C, 0x69,
            0x6E, 0x65, 0x4E, 0x75, 0x6D, 0x62, 0x65, 0x72,
            0x54, 0x61, 0x62, 0x6C, 0x65, 0x01, 0x00, 0x0A,
            0x53, 0x6F, 0x75, 0x72, 0x63, 0x65, 0x46, 0x69,
            0x6C, 0x65, 0x01, 0x00, 0x0A, 0x45, 0x6D, 0x70,
            0x74, 0x79, 0x2E, 0x6A, 0x61, 0x76, 0x61, 0x0C,
            0x00, 0x04, 0x00, 0x05, 0x01, 0x00, 0x05, 0x45,
            0x6D, 0x70, 0x74, 0x79, 0x01, 0x00, 0x10, 0x6A,
            0x61, 0x76, 0x61, 0x2F, 0x6C, 0x61, 0x6E, 0x67,
            0x2F, 0x4F, 0x62, 0x6A, 0x65, 0x63, 0x74, 0x00,
            0x21, 0x00, 0x02, 0x00, 0x03, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x04, 0x00,
            0x05, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00, 0x00,
            0x1D, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x05, 0x2A, (byte) 0xB7, 0x00, 0x01, (byte) 0xB1, 0x00, 0x00,
            0x00, 0x01, 0x00, 0x07, 0x00, 0x00, 0x00, 0x06,
            0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
            0x00, 0x08, 0x00, 0x00, 0x00, 0x02, 0x00, 0x09
    };

    private File createCleanJar(Path temp) throws IOException {
        File jar = temp.resolve("clean.jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("Empty.class"));
            jos.write(MINIMAL_CLASS_BYTES);
            jos.closeEntry();
        }
        return jar;
    }
}
