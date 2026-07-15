package com.lingframe.core.security;

import com.lingframe.core.security.AsmDangerousApiScanner.ScanResult;
import com.lingframe.core.security.AsmDangerousApiScanner.Violation;
import com.lingframe.core.security.AsmDangerousApiScanner.ViolationType;
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

    // ==================== 危险 API 覆盖度（P2-5）====================

    /**
     * 生成一个调用指定方法的类字节码。
     * scanner 只读取指令不执行代码，因此无需构造合法操作数栈。
     */
    private static byte[] generateClassWithCall(String owner, String methodName, String desc, int opcode) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Evil", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "doEvil", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(opcode, owner, methodName, desc, opcode == Opcodes.INVOKEINTERFACE);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ScanResult scanClassBytes(Path tempDir, byte[] classBytes) throws IOException {
        Files.write(tempDir.resolve("Evil.class"), classBytes);
        return AsmDangerousApiScanner.scan(tempDir.toFile());
    }

    @Nested
    @DisplayName("禁止 API 检测（FORBIDDEN）")
    class ForbiddenApiDetection {

        @Test
        @DisplayName("System.exit 触发 CRITICAL")
        void systemExitIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/System", "exit", "(I)V", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Runtime.exit 触发 CRITICAL")
        void runtimeExitIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "exit", "(I)V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Runtime.halt 触发 CRITICAL")
        void runtimeHaltIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "halt", "(I)V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Thread.stop 触发 CRITICAL（覆盖无参重载）")
        void threadStopIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Thread", "stop", "()V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Thread.stop(Throwable) 触发 CRITICAL（覆盖有参重载）")
        void threadStopWithArgIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Thread", "stop", "(Ljava/lang/Throwable;)V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Thread.suspend 触发 CRITICAL")
        void threadSuspendIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Thread", "suspend", "()V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Thread.resume 触发 CRITICAL")
        void threadResumeIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Thread", "resume", "()V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Runtime.load 触发 CRITICAL")
        void runtimeLoadIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "load", "(Ljava/lang/String;)V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Runtime.loadLibrary 触发 CRITICAL")
        void runtimeLoadLibraryIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "loadLibrary", "(Ljava/lang/String;)V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("System.load 触发 CRITICAL")
        void systemLoadIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/System", "load", "(Ljava/lang/String;)V", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("System.loadLibrary 触发 CRITICAL")
        void systemLoadLibraryIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Thread.stop0 触发 CRITICAL")
        void threadStop0IsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Thread", "stop0", "()V", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }
    }

    @Nested
    @DisplayName("警告 API 检测（WARN）")
    class WarnApiDetection {

        @Test
        @DisplayName("Runtime.exec 触发 WARNING 而非 CRITICAL")
        void runtimeExecIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "exec",
                    "(Ljava/lang/String;)Ljava/lang/Process;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
            assertFalse(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("ProcessBuilder.start 触发 WARNING")
        void processBuilderStartIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/ProcessBuilder", "start",
                    "()Ljava/lang/Process;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("ProcessBuilder 构造器触发 WARNING")
        void processBuilderInitIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/ProcessBuilder", "<init>",
                    "([Ljava/lang/String;)V", Opcodes.INVOKESPECIAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("Class.forName 触发 WARNING")
        void classForNameIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("ClassLoader.defineClass 触发 WARNING")
        void classLoaderDefineClassIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/ClassLoader", "defineClass",
                    "([BII)Ljava/lang/Class;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("ClassLoader.loadClass 触发 WARNING")
        void classLoaderLoadClassIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/ClassLoader", "loadClass",
                    "(Ljava/lang/String;)Ljava/lang/Class;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("InitialContext.lookup 触发 WARNING（JNDI 注入）")
        void jndiLookupIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("javax/naming/InitialContext", "lookup",
                    "(Ljava/lang/String;)Ljava/lang/Object;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("ScriptEngine.eval 触发 WARNING（脚本执行）")
        void scriptEngineEvalIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("javax/script/ScriptEngine", "eval",
                    "(Ljava/lang/String;)Ljava/lang/Object;", Opcodes.INVOKEINTERFACE);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("MethodHandles.lookup 触发 WARNING")
        void methodHandlesLookupIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/invoke/MethodHandles", "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("System.setSecurityManager 触发 WARNING")
        void setSecurityManagerIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/System", "setSecurityManager",
                    "(Ljava/lang/SecurityManager;)V", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("FileOutputStream 构造器触发 WARNING")
        void fileOutputStreamInitIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/io/FileOutputStream", "<init>",
                    "(Ljava/lang/String;)V", Opcodes.INVOKESPECIAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("Socket 构造器触发 WARNING")
        void socketInitIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/net/Socket", "<init>",
                    "(Ljava/lang/String;I)V", Opcodes.INVOKESPECIAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
        }
    }

    @Nested
    @DisplayName("告警去重")
    class NoDuplicateWarnings {

        @Test
        @DisplayName("同一 API 不产生重复告警（WARN_METHODS 与 WARN_PREFIXES 去重）")
        void noDuplicateWarningForOverlappingRules(@TempDir Path temp) throws IOException {
            // Class.forName 同时在 WARN_METHODS 和 WARN_PREFIXES 中，应只产生一条告警
            byte[] bytes = generateClassWithCall("java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
            assertEquals(1, result.getWarnings().size(), "同一 API 不应产生重复告警");
        }

        @Test
        @DisplayName("reflect/Method.invoke 不产生重复告警")
        void noDuplicateForMethodInvoke(@TempDir Path temp) throws IOException {
            // Method.invoke 同时在 WARN_METHODS 和 WARN_PREFIXES 中
            byte[] bytes = generateClassWithCall("java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasWarnings());
            assertEquals(1, result.getWarnings().size(), "同一 API 不应产生重复告警");
        }
    }
}
