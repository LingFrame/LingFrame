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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AsmDangerousApiScanner 测试。
 * 覆盖：禁止 API 检测、警告 API 检测、JAR/目录扫描、空输入、ScanResult 契约。
 */
@DisplayName("AsmDangerousApiScanner 测试")
class AsmDangerousApiScannerTest {

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

    // ==================== 空输入 ====================

    @Nested
    @DisplayName("空输入")
    class EmptyInput {

        @Test
        @DisplayName("null 输入返回空结果")
        void nullInputReturnsEmpty() throws IOException {
            ScanResult result = AsmDangerousApiScanner.scan(null, null);
            assertTrue(result.getErrors().isEmpty());
            assertTrue(result.getWarnings().isEmpty());
        }

        @Test
        @DisplayName("不存在的文件返回空结果")
        void nonExistentFileReturnsEmpty() throws IOException {
            ScanResult result = AsmDangerousApiScanner.scan(new File("nonexistent.jar"), null);
            assertTrue(result.getErrors().isEmpty());
            assertTrue(result.getWarnings().isEmpty());
        }

        @Test
        @DisplayName("非 JAR 非 DIR 文件返回空结果")
        void nonJarNonDirReturnsEmpty(@TempDir Path temp) throws IOException {
            File txt = temp.resolve("test.txt").toFile();
            txt.createNewFile();
            ScanResult result = AsmDangerousApiScanner.scan(txt, null);
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

            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(), null);
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
            ScanResult result = AsmDangerousApiScanner.scan(jar, null);
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
            ScanResult result = AsmDangerousApiScanner.scan(jar, null);
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

    // ==================== 危险 API 覆盖度 ====================

    /**
     * 生成一个调用指定方法的类字节码。
     * scanner 只读取指令不执行代码，因此无需构造合法操作数栈。
     */
    private static byte[] generateClassWithCall(String owner, String methodName, String desc, int opcode) {
        return generateClassWithCallNamed("Evil", owner, methodName, desc, opcode);
    }

    /**
     * 生成指定类名、调用指定方法的类字节码。用于测试包前缀豁免。
     */
    private static byte[] generateClassWithCallNamed(String className, String owner, String methodName,
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

    private static ScanResult scanClassBytes(Path tempDir, byte[] classBytes) throws IOException {
        Files.write(tempDir.resolve("Evil.class"), classBytes);
        return AsmDangerousApiScanner.scan(tempDir.toFile(), null);
    }

    /**
     * 把类字节码写到 tempDir 下指定相对路径（含包目录），扫描目录。
     * 用于验证包前缀豁免：类路径形如 com/fasterxml/jackson/databind/ObjectMapper.class。
     */
    private static ScanResult scanClassBytesAt(Path tempDir, String relativeClassPath, byte[] classBytes)
            throws IOException {
        Path classFile = tempDir.resolve(relativeClassPath.replace('/', File.separatorChar));
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, classBytes);
        return AsmDangerousApiScanner.scan(tempDir.toFile(), null);
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
            byte[] bytes = generateClassWithCall("java/lang/Thread", "stop", "(Ljava/lang/Throwable;)V",
                    Opcodes.INVOKEVIRTUAL);
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
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "load", "(Ljava/lang/String;)V",
                    Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("Runtime.loadLibrary 触发 CRITICAL")
        void runtimeLoadLibraryIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "loadLibrary", "(Ljava/lang/String;)V",
                    Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("System.load 触发 CRITICAL")
        void systemLoadIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/System", "load", "(Ljava/lang/String;)V",
                    Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("System.loadLibrary 触发 CRITICAL")
        void systemLoadLibraryIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/System", "loadLibrary", "(Ljava/lang/String;)V",
                    Opcodes.INVOKESTATIC);
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

        @Test
        @DisplayName("Runtime.exec 已提升为 CRITICAL")
        void runtimeExecIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/Runtime", "exec",
                    "(Ljava/lang/String;)Ljava/lang/Process;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
            assertFalse(result.hasWarnings());
        }

        @Test
        @DisplayName("ProcessBuilder.start 已提升为 CRITICAL")
        void processBuilderStartIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/ProcessBuilder", "start",
                    "()Ljava/lang/Process;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("ProcessBuilder 构造器已提升为 CRITICAL")
        void processBuilderInitIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/ProcessBuilder", "<init>",
                    "([Ljava/lang/String;)V", Opcodes.INVOKESPECIAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("InitialContext.lookup 触发 CRITICAL（JNDI 注入）")
        void jndiLookupIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("javax/naming/InitialContext", "lookup",
                    "(Ljava/lang/String;)Ljava/lang/Object;", Opcodes.INVOKEVIRTUAL);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("ScriptEngine.eval 触发 CRITICAL（脚本执行）")
        void scriptEngineEvalIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("javax/script/ScriptEngine", "eval",
                    "(Ljava/lang/String;)Ljava/lang/Object;", Opcodes.INVOKEINTERFACE);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }

        @Test
        @DisplayName("System.setSecurityManager 触发 CRITICAL")
        void setSecurityManagerIsForbidden(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/System", "setSecurityManager",
                    "(Ljava/lang/SecurityManager;)V", Opcodes.INVOKESTATIC);
            ScanResult result = scanClassBytes(temp, bytes);
            assertTrue(result.hasCriticalViolations());
        }
    }

    @Nested
    @DisplayName("警告 API 检测（WARN）")
    class WarnApiDetection {

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
        @DisplayName("MethodHandles.lookup 触发 WARNING")
        void methodHandlesLookupIsWarning(@TempDir Path temp) throws IOException {
            byte[] bytes = generateClassWithCall("java/lang/invoke/MethodHandles", "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;", Opcodes.INVOKESTATIC);
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

    // ==================== 依赖库包前缀豁免 ====================

    @Nested
    @DisplayName("依赖库包前缀豁免（trustedLibPrefixes）")
    class TrustedLibPrefixes {

        /**
         * 模拟 Jackson 反序列化调用 Method.invoke 的场景。
         * 类路径 com/fasterxml/jackson/databind/ObjectMapper 走反射链路。
         */
        private byte[] jacksonLikeClass() {
            return generateClassWithCallNamed(
                    "com/fasterxml/jackson/databind/ObjectMapper",
                    "java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                    Opcodes.INVOKEVIRTUAL);
        }

        @Test
        @DisplayName("不配置豁免时，依赖库类调用 Method.invoke 触发 WARNING")
        void noWhitelistTriggersWarning(@TempDir Path temp) throws IOException {
            ScanResult result = scanClassBytesAt(temp,
                    "com/fasterxml/jackson/databind/ObjectMapper.class", jacksonLikeClass());
            assertTrue(result.hasWarnings(), "未豁免时 Jackson 类的 Method.invoke 应触发 WARNING");
        }

        @Test
        @DisplayName("配置斜杠形式前缀豁免后，依赖库类被跳过，无 WARNING")
        void slashPrefixSkipsLib(@TempDir Path temp) throws IOException {
            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(),
                    Collections.singletonList("com/fasterxml/jackson/"));
            // 此时 temp 是空目录，应无违规
            assertFalse(result.hasWarnings());

            // 写入 Jackson 类后再扫描，应被前缀豁免
            scanClassBytesAt(temp, "com/fasterxml/jackson/databind/ObjectMapper.class", jacksonLikeClass());
            ScanResult result2 = AsmDangerousApiScanner.scan(temp.toFile(),
                    Collections.singletonList("com/fasterxml/jackson/"));
            assertFalse(result2.hasWarnings(), "豁免 com/fasterxml/jackson/ 后该类应被跳过");
            assertFalse(result2.hasCriticalViolations());
        }

        @Test
        @DisplayName("配置点号形式前缀（com.fasterxml.jackson.）也能归一化豁免")
        void dotPrefixNormalized(@TempDir Path temp) throws IOException {
            scanClassBytesAt(temp, "com/fasterxml/jackson/databind/ObjectMapper.class", jacksonLikeClass());
            // 点号形式 + 末尾点号
            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(),
                    Collections.singletonList("com.fasterxml.jackson."));
            assertFalse(result.hasWarnings(), "点号形式前缀应归一化为斜杠并生效");
        }

        @Test
        @DisplayName("配置点号形式前缀（无末尾点号）也能归一化豁免")
        void dotPrefixNoTrailingDotNormalized(@TempDir Path temp) throws IOException {
            scanClassBytesAt(temp, "com/fasterxml/jackson/databind/ObjectMapper.class", jacksonLikeClass());
            // 点号形式，无末尾点号
            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(),
                    Collections.singletonList("com.fasterxml.jackson"));
            assertFalse(result.hasWarnings(), "无末尾点号的点号前缀应归一化并生效");
        }

        @Test
        @DisplayName("豁免前缀不匹配其他包，不影响其他类的扫描")
        void whitelistDoesNotAffectOtherPackages(@TempDir Path temp) throws IOException {
            // 同时写入 Jackson 类（豁免）和普通 Evil 类（不豁免）
            scanClassBytesAt(temp, "com/fasterxml/jackson/databind/ObjectMapper.class", jacksonLikeClass());
            byte[] evilBytes = generateClassWithCall("java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.INVOKEVIRTUAL);
            Files.write(temp.resolve("Evil.class"), evilBytes);

            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(),
                    Collections.singletonList("com/fasterxml/jackson/"));
            // Evil.class 仍应触发 WARNING，Jackson 类被跳过
            assertTrue(result.hasWarnings(), "未命中豁免前缀的 Evil.class 应仍触发 WARNING");
            assertEquals(1, result.getWarnings().size(), "只有 Evil.class 触发 WARNING");
        }

        @Test
        @DisplayName("前缀匹配避免误伤：com.fasterxml 不应豁免 com.fasterxml.jackson 之外的 com.fasterxmlx")
        void prefixMatchIsStrict(@TempDir Path temp) throws IOException {
            // com/fastasterxml/ 与 com/fasterxml/ 不应混淆
            byte[] bytes = generateClassWithCallNamed(
                    "com/fastasterxml/evil/Evil",
                    "java/lang/reflect/Method", "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                    Opcodes.INVOKEVIRTUAL);
            scanClassBytesAt(temp, "com/fastasterxml/evil/Evil.class", bytes);

            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(),
                    Collections.singletonList("com/fasterxml/"));
            assertTrue(result.hasWarnings(), "com/fastasterxml 不应被 com/fasterxml/ 豁免误伤");
        }

        @Test
        @DisplayName("null trustedLibPrefixes 等价于不豁免")
        void nullWhitelistEqualsNoWhitelist(@TempDir Path temp) throws IOException {
            scanClassBytesAt(temp, "com/fasterxml/jackson/databind/ObjectMapper.class", jacksonLikeClass());
            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(), null);
            assertTrue(result.hasWarnings(), "null 前缀列表应等价于不豁免");
        }

        @Test
        @DisplayName("空列表 trustedLibPrefixes 等价于不豁免")
        void emptyWhitelistEqualsNoWhitelist(@TempDir Path temp) throws IOException {
            scanClassBytesAt(temp, "com/fasterxml/jackson/databind/ObjectMapper.class", jacksonLikeClass());
            ScanResult result = AsmDangerousApiScanner.scan(temp.toFile(), Collections.emptyList());
            assertTrue(result.hasWarnings(), "空前缀列表应等价于不豁免");
        }

        @Test
        @DisplayName("JAR 包内依赖库类同样按前缀豁免")
        void jarLibPrefixSkipped(@TempDir Path temp) throws IOException {
            File jar = temp.resolve("fat.jar").toFile();
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
                // 模拟胖包内打平的 Jackson 类
                jos.putNextEntry(new JarEntry("com/fasterxml/jackson/databind/ObjectMapper.class"));
                jos.write(jacksonLikeClass());
                jos.closeEntry();
                // 模拟灵元自己的 Evil 类
                jos.putNextEntry(new JarEntry("Evil.class"));
                jos.write(generateClassWithCall("java/lang/reflect/Method", "invoke",
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.INVOKEVIRTUAL));
                jos.closeEntry();
            }
            ScanResult result = AsmDangerousApiScanner.scan(jar,
                    Collections.singletonList("com/fasterxml/jackson/"));
            // Jackson 类被跳过，Evil 类仍触发 WARNING
            assertTrue(result.hasWarnings());
            assertEquals(1, result.getWarnings().size(), "JAR 内只有 Evil.class 触发 WARNING");
        }
    }
}
