package com.lingframe.core.security;

import com.lingframe.api.exception.LingException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * 使用 ASM 进行精确的危险 API 检测
 */
@Slf4j
public class AsmDangerousApiScanner {

    private static final Set<String> FORBIDDEN_METHODS = Set.of(
            "java/lang/System.exit(I)V",
            "java/lang/Runtime.exit(I)V",
            "java/lang/Runtime.halt(I)V");

    private static final Set<String> WARN_METHODS = Set.of(
            "java/lang/Runtime.exec",
            "java/lang/ProcessBuilder.start");

    public static ScanResult scan(File source) throws IOException {
        if (source.isDirectory()) {
            return scanDirectory(source);
        } else if (source.getName().endsWith(".jar")) {
            return scanJar(source);
        }
        return new ScanResult(Collections.emptyList(), Collections.emptyList());
    }

    private static ScanResult scanJar(File jarFile) throws IOException {
        List<Violation> errors = new ArrayList<>();
        List<Violation> warnings = new ArrayList<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        scanClass(entry.getName(), is, errors, warnings);
                    }
                }
            }
        }

        return new ScanResult(errors, warnings);
    }

    private static ScanResult scanDirectory(File dir) throws IOException {
        List<Violation> errors = new ArrayList<>();
        List<Violation> warnings = new ArrayList<>();
        scanDirRecursive(dir, dir, errors, warnings);
        return new ScanResult(errors, warnings);
    }

    private static void scanDirRecursive(File root, File dir,
            List<Violation> errors,
            List<Violation> warnings) throws IOException {
        File[] files = dir.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirRecursive(root, file, errors, warnings);
            } else if (file.getName().endsWith(".class")) {
                String relativePath = root.toPath().relativize(file.toPath()).toString();
                try (FileInputStream fis = new FileInputStream(file)) {
                    scanClass(relativePath, fis, errors, warnings);
                }
            }
        }
    }

    private static void scanClass(String className, InputStream is,
            List<Violation> errors,
            List<Violation> warnings) throws IOException {
        ClassReader reader = new ClassReader(is);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {

            private String currentClass;

            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                this.currentClass = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String desc, boolean isInterface) {
                        String fullMethod = owner + "." + methodName + desc;
                        String methodPrefix = owner + "." + methodName;

                        // 检查禁止的方法
                        if (FORBIDDEN_METHODS.contains(fullMethod)) {
                            errors.add(new Violation(
                                    currentClass,
                                    fullMethod,
                                    ViolationType.CRITICAL,
                                    "Forbidden API: This call would terminate the JVM"));
                        }

                        // 检查警告的方法
                        for (String warn : WARN_METHODS) {
                            if (methodPrefix.startsWith(warn)) {
                                warnings.add(new Violation(
                                        currentClass,
                                        fullMethod,
                                        ViolationType.WARNING,
                                        "Potentially dangerous API: Process execution"));
                                break;
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    // ==================== 结果类 ====================

    public enum ViolationType {
        CRITICAL, WARNING
    }

    public record Violation(
            String className,
            String apiCall,
            ViolationType type,
            String message) {
        @NonNull
        @Override
        public String toString() {
            return String.format("[%s] %s in %s: %s", type, apiCall, className, message);
        }
    }

    public record ScanResult(List<Violation> errors, List<Violation> warnings) {

        public boolean hasCriticalViolations() {
            return !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        public void throwIfCritical() {
            if (hasCriticalViolations()) {
                String msg = errors.stream()
                        .map(Violation::toString)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
                throw new LingException("Plugin security check failed:\n" + msg);
            }
        }

        public void logWarnings() {
            if (hasWarnings()) {
                log.warn("Plugin security warnings:");
                warnings.forEach(w -> log.warn("  {}", w));
            }
        }
    }
}