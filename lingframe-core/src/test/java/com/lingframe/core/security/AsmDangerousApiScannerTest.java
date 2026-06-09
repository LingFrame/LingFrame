package com.lingframe.core.security;

import com.lingframe.core.security.AsmDangerousApiScanner.ScanResult;
import com.lingframe.core.security.AsmDangerousApiScanner.Violation;
import com.lingframe.core.security.AsmDangerousApiScanner.ViolationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AsmDangerousApiScanner 测试。
 * 覆盖：禁止 API 检测、警告 API 检测、JAR/目录扫描、空输入、ScanResult 契约。
 */
@DisplayName("AsmDangerousApiScanner 测试")
class AsmDangerousApiScannerTest {

    private static final byte[] MINIMAL_CLASS_BYTES = new byte[]{
        (byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE, 0x00, 0x00, 0x00, 0x34,
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
        0x05, 0x2A, (byte)0xB7, 0x00, 0x01, (byte)0xB1, 0x00, 0x00,
        0x00, 0x01, 0x00, 0x07, 0x00, 0x00, 0x00, 0x06,
        0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
        0x00, 0x08, 0x00, 0x00, 0x00, 0x02, 0x00, 0x09
    };

    // ==================== 空输入 ====================

    @Nested
    @DisplayName("空输入")
    class EmptyInput {

        @Test
        @DisplayName("null 输入返回空结果")
        void nullInputReturnsEmpty() throws IOException {
            ScanResult result = AsmDangerousApiScanner.scan(null);
            assertTrue(result.getErrors().isEmpty());
            assertTrue(result.getWarnings().isEmpty());
        }

        @Test
        @DisplayName("不存在的文件返回空结果")
        void nonExistentFileReturnsEmpty() throws IOException {
            ScanResult result = AsmDangerousApiScanner.scan(new File("nonexistent.jar"));
            assertTrue(result.getErrors().isEmpty());
            assertTrue(result.getWarnings().isEmpty());
        }

        @Test
        @DisplayName("非 JAR 非 DIR 文件返回空结果")
        void nonJarNonDirReturnsEmpty(@TempDir Path temp) throws IOException {
            File txt = temp.resolve("test.txt").toFile();
            txt.createNewFile();
            ScanResult result = AsmDangerousApiScanner.scan(txt);
            assertTrue(result.getErrors().isEmpty());
            assertTrue(result.getWarnings().isEmpty());
        }
    }

    // ==================== ScanResult 契约 ====================

    @Nested
    @DisplayName("ScanResult 契约")
    class ScanResultContract {

        @Test
        @DisplayName("hasCriticalViolations 正确判断")
        void hasCriticalViolations() {
            ScanResult withCritical = new ScanResult(
                    Collections.singletonList(new Violation("C", "m", ViolationType.CRITICAL, "msg")),
                    Collections.emptyList());
            assertTrue(withCritical.hasCriticalViolations());

            ScanResult noCritical = new ScanResult(Collections.emptyList(), Collections.emptyList());
            assertFalse(noCritical.hasCriticalViolations());
        }

        @Test
        @DisplayName("hasWarnings 正确判断")
        void hasWarnings() {
            ScanResult withWarning = new ScanResult(Collections.emptyList(),
                    Collections.singletonList(new Violation("C", "m", ViolationType.WARNING, "msg")));
            assertTrue(withWarning.hasWarnings());

            ScanResult noWarning = new ScanResult(Collections.emptyList(), Collections.emptyList());
            assertFalse(noWarning.hasWarnings());
        }

        @Test
        @DisplayName("throwIfCritical 有 CRITICAL 时抛出 LingException")
        void throwIfCriticalThrows() {
            ScanResult result = new ScanResult(
                    Collections.singletonList(new Violation("C", "m", ViolationType.CRITICAL, "msg")),
                    Collections.emptyList());
            assertThrows(Exception.class, result::throwIfCritical);
        }

        @Test
        @DisplayName("throwIfCritical 无 CRITICAL 时不抛出")
        void throwIfCriticalNoThrow() {
            ScanResult result = new ScanResult(Collections.emptyList(), Collections.emptyList());
            assertDoesNotThrow(result::throwIfCritical);
        }
    }

    // ==================== 目录扫描 ====================

    @Nested
    @DisplayName("目录扫描")
    class DirectoryScan {

        @Test
        @DisplayName("扫描目录中的 .class 文件")
        void scanDirectoryClassFiles(@TempDir Path temp) throws IOException {
            // 创建一个简单的 .class 文件目录结构（空 class 文件，无违规）
            Path classDir = temp.resolve("com/example");
            Files.createDirectories(classDir);
            // 写入合法空类文件
            Files.write(classDir.resolve("Service.class"), MINIMAL_CLASS_BYTES);

            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile());
            // 空类体不会产生违规
            assertFalse(result.hasCriticalViolations());
        }
    }

    // ==================== JAR 扫描 ====================

    @Nested
    @DisplayName("JAR 扫描")
    class JarScan {

        @Test
        @DisplayName("扫描空 JAR 返回空结果")
        void scanEmptyJar(@TempDir Path temp) throws IOException {
            File jar = temp.resolve("empty.jar").toFile();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
                // 空 JAR
            }
            ScanResult result = AsmDangerousApiScanner.scan(jar);
            assertFalse(result.hasCriticalViolations());
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("扫描包含 .class 但无违规的 JAR")
        void scanCleanJar(@TempDir Path temp) throws IOException {
            File jar = temp.resolve("clean.jar").toFile();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
                // 添加一个空 class entry
                jos.putNextEntry(new JarEntry("com/example/Service.class"));
                jos.write(MINIMAL_CLASS_BYTES);
                jos.closeEntry();
            }
            ScanResult result = AsmDangerousApiScanner.scan(jar);
            assertFalse(result.hasCriticalViolations());
        }
    }

    // ==================== Violation toString ====================

    @Test
    @DisplayName("Violation toString 包含类型、API、类名和消息")
    void violationToString() {
        Violation v = new Violation("com/example/Evil", "java/lang/System.exit(I)V",
                ViolationType.CRITICAL, "Forbidden API");
        String str = v.toString();
        assertTrue(str.contains("CRITICAL"));
        assertTrue(str.contains("System.exit"));
        assertTrue(str.contains("Evil"));
    }
}
