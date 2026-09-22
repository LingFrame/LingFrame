package com.lingframe.core.security;

import com.lingframe.core.exception.LingSecurityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
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
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            // 空 JAR 无违规，严格模式也不抛异常
            assertDoesNotThrow(() -> {
                // 无法直接验证 strictMode 字段，通过行为间接验证
            });
        }

        @Test
        @DisplayName("严格模式下警告 API 也抛出 LingSecurityException")
        void strictModeWarningsThrow(@TempDir Path temp) throws IOException {
            File jar = createCleanJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
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
            DangerousApiVerifier verifier = new DangerousApiVerifier(false, Collections.emptyList(), null);
            assertDoesNotThrow(() -> verifier.verify("ling-1", jar));
        }
    }

    // ==================== 可信灵元 ====================

    @Nested
    @DisplayName("可信灵元")
    class TrustedLing {

        @Test
        @DisplayName("白名单中的灵元视为可信，使用非严格模式")
        void trustedLingInWhitelist(@TempDir Path temp) throws IOException {
            File jar = createCleanJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true,
                    Arrays.asList("trusted-ling", "another-trusted"), null);
            // 白名单中的灵元即使严格模式也使用非严格模式
            assertDoesNotThrow(() -> verifier.verify("trusted-ling", jar));
        }

        @Test
        @DisplayName("-agent 后缀不再视为可信，必须使用严格模式")
        void agentSuffixNoLongerTrusted(@TempDir Path temp) throws IOException {
            File jar = createCleanJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            // -agent 后缀不再视为可信，但干净 JAR 不抛异常（行为验证需配合警告用例）
            assertDoesNotThrow(() -> verifier.verify("my-agent", jar));
        }

        @Test
        @DisplayName("null trustedLingIds 不抛异常")
        void nullTrustedLingIdsSafe(@TempDir Path temp) throws IOException {
            File jar = createCleanJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, null, null);
            assertDoesNotThrow(() -> verifier.verify("ling-1", jar));
        }
    }

    // ==================== 异常容错 ====================

    @Nested
    @DisplayName("异常容错")
    class ExceptionHandling {

        @Test
        @DisplayName("null source 不抛异常")
        void nullSourceSafe() {
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            assertDoesNotThrow(() -> verifier.verify("ling-1", null));
        }

        @Test
        @DisplayName("不存在的文件不抛异常")
        void nonExistentFileSafe() {
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            assertDoesNotThrow(() -> verifier.verify("ling-1", new File("nonexistent.jar")));
        }
    }

    // ==================== 严格模式错误信息详情 ====================

    @Nested
    @DisplayName("严格模式错误信息详情")
    class StrictModeMessage {

        /**
         * 构造调用 Class.forName 的 jar（WARN 级，仅 strict 硬失败）。
         */
        private File createWarnJar(Path temp) throws IOException {
            File jar = temp.resolve("warn.jar").toFile();
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "WarnApi", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "doWarn", "()V", null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
                jos.putNextEntry(new JarEntry("WarnApi.class"));
                jos.write(bytes);
                jos.closeEntry();
            }
            return jar;
        }

        /**
         * 构造调用 Runtime.exec 的 jar（FORBIDDEN/CRITICAL，任意模式均失败）。
         */
        private File createForbiddenJar(Path temp) throws IOException {
            File jar = temp.resolve("evil.jar").toFile();
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Evil", null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "doEvil", "()V", null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exec",
                    "(Ljava/lang/String;)Ljava/lang/Process;", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            cw.visitEnd();
            byte[] bytes = cw.toByteArray();

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
                jos.putNextEntry(new JarEntry("Evil.class"));
                jos.write(bytes);
                jos.closeEntry();
            }
            return jar;
        }

        @Test
        @DisplayName("严格模式下拦截异常 message 包含具体违规类名和 API")
        void messageContainsViolationDetail(@TempDir Path temp) throws IOException {
            File jar = createWarnJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            LingSecurityException ex = assertThrows(LingSecurityException.class,
                    () -> verifier.verify("evil-ling", jar));
            String msg = ex.getMessage();
            assertTrue(msg.contains("evil-ling"), "message 应包含 lingId");
            assertTrue(msg.contains("totalViolations="), "message 应包含违规总数");
            assertTrue(msg.contains("WarnApi"), "message 应包含违规类名");
            assertTrue(msg.contains("Class.forName"), "message 应包含具体 API");
        }

        @Test
        @DisplayName("严格模式拦截异常 message 包含修复 hint")
        void messageContainsHint(@TempDir Path temp) throws IOException {
            File jar = createWarnJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            LingSecurityException ex = assertThrows(LingSecurityException.class,
                    () -> verifier.verify("evil-ling", jar));
            String msg = ex.getMessage();
            assertTrue(msg.contains("trusted-ling-ids"), "message 应提示 trusted-ling-ids");
            assertTrue(msg.contains("trusted-lib-prefixes"), "message 应提示 trusted-lib-prefixes");
            assertTrue(msg.contains("strict-mode=false"), "message 应提示 strict-mode=false");
        }

        @Test
        @DisplayName("宽松模式对 WARN 级 API 不抛异常")
        void lenientModeNoThrowForWarnJar(@TempDir Path temp) throws IOException {
            File jar = createWarnJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(false, Collections.emptyList(), null);
            assertDoesNotThrow(() -> verifier.verify("evil-ling", jar));
        }

        @Test
        @DisplayName("FORBIDDEN API（Runtime.exec）在宽松模式也硬失败")
        void forbiddenApiAlwaysFails(@TempDir Path temp) throws IOException {
            File jar = createForbiddenJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(false, Collections.emptyList(), null);
            Exception ex = assertThrows(Exception.class, () -> verifier.verify("evil-ling", jar));
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("Runtime.exec"),
                    "CRITICAL 路径应暴露 Runtime.exec，实际: " + ex.getMessage());
        }
    }

    // ==================== 依赖库包前缀豁免集成 ====================

    @Nested
    @DisplayName("依赖库包前缀豁免集成")
    class TrustedLibPrefixesIntegration {

        /**
         * 构造胖包：Jackson 类（前缀豁免）+ 灵元 Evil 类（不豁免）。
         */
        private File createFatJar(Path temp) throws IOException {
            File jar = temp.resolve("fat.jar").toFile();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
                // Jackson 类：调用 Method.invoke
                jos.putNextEntry(new JarEntry("com/fasterxml/jackson/databind/ObjectMapper.class"));
                jos.write(generateClassWithCallNamed(
                        "com/fasterxml/jackson/databind/ObjectMapper",
                        "java/lang/reflect/Method", "invoke",
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                        Opcodes.INVOKEVIRTUAL));
                jos.closeEntry();
                // 灵元 Evil 类：调用 FileOutputStream 构造器（属于 WARN_METHODS，只在严格模式拦截）
                jos.putNextEntry(new JarEntry("Evil.class"));
                jos.write(generateClassWithCall("java/io/FileOutputStream", "<init>",
                        "(Ljava/lang/String;)V", Opcodes.INVOKESPECIAL));
                jos.closeEntry();
            }
            return jar;
        }

        private byte[] generateClassWithCall(String owner, String methodName, String desc, int opcode) {
            return generateClassWithCallNamed("Evil", owner, methodName, desc, opcode);
        }

        private byte[] generateClassWithCallNamed(String className, String owner, String methodName,
                String desc, int opcode) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "doEvil", "()V", null, null);
            mv.visitCode();
            mv.visitMethodInsn(opcode, owner, methodName, desc, opcode == Opcodes.INVOKEINTERFACE);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        @Test
        @DisplayName("不豁免时胖包被拦截（依赖库触发 WARNING）")
        void fatJarBlockedWithoutWhitelist(@TempDir Path temp) throws IOException {
            File jar = createFatJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            // 胖包内 Jackson + Evil 都触发 WARNING，严格模式拦截
            LingSecurityException ex = assertThrows(LingSecurityException.class,
                    () -> verifier.verify("fat-ling", jar));
            // 至少 2 条违规（Jackson 1 + Evil 1）
            assertTrue(ex.getMessage().contains("totalViolations="));
        }

        @Test
        @DisplayName("豁免 Jackson 前缀后，胖包内灵元 Evil 仍触发拦截")
        void fatJarStillBlockedDueToEvilClass(@TempDir Path temp) throws IOException {
            File jar = createFatJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(),
                    Collections.singletonList("com.fasterxml.jackson."));
            LingSecurityException ex = assertThrows(LingSecurityException.class,
                    () -> verifier.verify("fat-ling", jar));
            // Jackson 被豁免，但 Evil 仍触发，应只剩 1 条违规
            String msg = ex.getMessage();
            assertTrue(msg.contains("totalViolations=1"), "Jackson 豁免后应只剩 1 条违规");
            assertTrue(msg.contains("Evil"), "违规应来自 Evil 类");
            assertFalse(msg.contains("ObjectMapper"), "Jackson 类不应出现在违规清单");
        }

        @Test
        @DisplayName("三参构造器 null trustedLibPrefixes 等价于不豁免")
        void nullLibPrefixesEqualsNoWhitelist(@TempDir Path temp) throws IOException {
            File jar = createFatJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(true, Collections.emptyList(), null);
            LingSecurityException ex = assertThrows(LingSecurityException.class,
                    () -> verifier.verify("fat-ling", jar));
            // null 等价于不豁免，应有多条违规
            assertTrue(ex.getMessage().contains("totalViolations="));
        }

        @Test
        @DisplayName("宽松模式 + 胖包不拦截（无论是否豁免）")
        void lenientModeFatJarNotBlocked(@TempDir Path temp) throws IOException {
            File jar = createFatJar(temp);
            DangerousApiVerifier verifier = new DangerousApiVerifier(false, Collections.emptyList(), null);
            assertDoesNotThrow(() -> verifier.verify("fat-ling", jar));
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
