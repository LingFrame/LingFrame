package com.lingframe.core.security;

import com.lingframe.api.exception.LingException;
import lombok.NonNull;
import lombok.Value;
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

    private static final Set<String> FORBIDDEN_METHODS;
    private static final Set<String> WARN_METHODS;
    private static final Set<String> WARN_PREFIXES;
    /** 前缀 → 风险分类的显式映射，避免 contains() 误分类 */
    private static final Map<String, String> PREFIX_CATEGORY;

    static {
        // FORBIDDEN_METHODS：绝对禁止（按 owner.name 匹配，覆盖所有重载）
        FORBIDDEN_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                // JVM 终止
                "java/lang/System.exit",
                "java/lang/Runtime.exit",
                "java/lang/Runtime.halt",
                // 线程暴力停止/挂起（已废弃，导致状态不一致）
                "java/lang/Thread.stop",
                "java/lang/Thread.suspend",
                "java/lang/Thread.resume",
                "java/lang/Thread.stop0",
                "java/lang/Thread.suspend0",
                "java/lang/Thread.resume0",
                // 本地库加载（绕过安全管理）
                "java/lang/Runtime.load",
                "java/lang/Runtime.loadLibrary",
                "java/lang/System.load",
                "java/lang/System.loadLibrary"
        )));

        // WARN_METHODS：高危警告（严格模式下抛异常）
        WARN_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                // 进程执行
                "java/lang/Runtime.exec",
                "java/lang/ProcessBuilder.start",
                "java/lang/ProcessBuilder.<init>",
                // 反射类加载
                "java/lang/Class.forName",
                "java/lang/ClassLoader.loadClass",
                "java/lang/ClassLoader.defineClass",
                "java/lang/ClassLoader.defineClass0",
                "java/lang/ClassLoader.defineClass1",
                "java/lang/ClassLoader.defineClass2",
                "java/lang/reflect/Method.invoke",
                "java/lang/Class.getDeclaredMethod",
                "java/lang/Class.getMethod",
                // 安全管理器篡改
                "java/lang/System.setSecurityManager",
                "java/lang/SecurityManager.checkPackageAccess",
                // JNDI 注入
                "javax/naming/InitialContext.lookup",
                "javax/naming/Context.lookup",
                // 脚本执行
                "javax/script/ScriptEngine.eval",
                // 网络连接
                "java/net/URL.openConnection",
                "java/net/Socket.<init>",
                "java/net/ServerSocket.<init>",
                // 文件写入
                "java/io/FileOutputStream.<init>",
                "java/io/FileWriter.<init>",
                "java/io/RandomAccessFile.<init>",
                // 反射字段篡改
                "java/lang/reflect/Field.set",
                "java/lang/reflect/Field.setAccessible",
                "java/lang/reflect/AccessibleObject.setAccessible",
                // 方法句柄
                "java/lang/invoke/MethodHandles.lookup"
        )));

        // 前缀匹配的警告规则（反射、Unsafe、文件/网络 I/O）
        Set<String> warnPrefixes = new LinkedHashSet<>();
        Map<String, String> categoryMap = new LinkedHashMap<>();

        // 反射操作
        String catReflection = "Reflection";
        warnPrefixes.add("java/lang/Class.forName");           categoryMap.put("java/lang/Class.forName", catReflection);
        warnPrefixes.add("java/lang/reflect/AccessibleObject.setAccessible"); categoryMap.put("java/lang/reflect/AccessibleObject.setAccessible", catReflection);
        warnPrefixes.add("java/lang/reflect/Method.invoke");   categoryMap.put("java/lang/reflect/Method.invoke", catReflection);
        warnPrefixes.add("java/lang/reflect/Constructor.newInstance"); categoryMap.put("java/lang/reflect/Constructor.newInstance", catReflection);
        warnPrefixes.add("java/lang/reflect/Field.set");       categoryMap.put("java/lang/reflect/Field.set", catReflection);
        warnPrefixes.add("java/lang/reflect/Field.get");       categoryMap.put("java/lang/reflect/Field.get", catReflection);

        // Unsafe
        String catUnsafe = "sun.misc.Unsafe";
        warnPrefixes.add("sun/misc/Unsafe.");                  categoryMap.put("sun/misc/Unsafe.", catUnsafe);

        // 文件 I/O
        String catFileIO = "File I/O";
        warnPrefixes.add("java/io/FileInputStream.<init>");    categoryMap.put("java/io/FileInputStream.<init>", catFileIO);
        warnPrefixes.add("java/io/FileOutputStream.<init>");   categoryMap.put("java/io/FileOutputStream.<init>", catFileIO);
        warnPrefixes.add("java/io/FileReader.<init>");         categoryMap.put("java/io/FileReader.<init>", catFileIO);
        warnPrefixes.add("java/io/FileWriter.<init>");         categoryMap.put("java/io/FileWriter.<init>", catFileIO);
        warnPrefixes.add("java/io/RandomAccessFile.<init>");   categoryMap.put("java/io/RandomAccessFile.<init>", catFileIO);
        warnPrefixes.add("java/nio/file/Files.write");         categoryMap.put("java/nio/file/Files.write", catFileIO);
        warnPrefixes.add("java/nio/file/Files.delete");        categoryMap.put("java/nio/file/Files.delete", catFileIO);
        warnPrefixes.add("java/nio/file/Files.move");          categoryMap.put("java/nio/file/Files.move", catFileIO);

        // 网络 I/O
        String catNetworkIO = "Network I/O";
        warnPrefixes.add("java/net/Socket.<init>");            categoryMap.put("java/net/Socket.<init>", catNetworkIO);
        warnPrefixes.add("java/net/ServerSocket.<init>");      categoryMap.put("java/net/ServerSocket.<init>", catNetworkIO);
        warnPrefixes.add("java/net/DatagramSocket.<init>");    categoryMap.put("java/net/DatagramSocket.<init>", catNetworkIO);
        warnPrefixes.add("java/net/URL.openStream");           categoryMap.put("java/net/URL.openStream", catNetworkIO);
        warnPrefixes.add("java/net/URL.openConnection");       categoryMap.put("java/net/URL.openConnection", catNetworkIO);
        warnPrefixes.add("java/net/HttpURLConnection.connect"); categoryMap.put("java/net/HttpURLConnection.connect", catNetworkIO);

        WARN_PREFIXES = Collections.unmodifiableSet(warnPrefixes);
        PREFIX_CATEGORY = Collections.unmodifiableMap(categoryMap);
    }

    public static ScanResult scan(File source) throws IOException {
        if (source == null || !source.exists()) {
            return new ScanResult(Collections.emptyList(), Collections.emptyList());
        }
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

                        // 检查禁止的方法（按 owner.name 精确匹配，覆盖所有重载）
                        if (FORBIDDEN_METHODS.contains(methodPrefix)) {
                            errors.add(new Violation(
                                    currentClass,
                                    fullMethod,
                                    ViolationType.CRITICAL,
                                    "Forbidden API: this call is categorically prohibited"));
                        }

                        boolean warned = false;
                        // 检查警告的方法（前缀匹配）
                        for (String warn : WARN_METHODS) {
                            if (methodPrefix.startsWith(warn)) {
                                warnings.add(new Violation(
                                        currentClass,
                                        fullMethod,
                                        ViolationType.WARNING,
                                        "Potentially dangerous API"));
                                warned = true;
                                break;
                            }
                        }

                        // 检查扩展警告规则（前缀匹配：反射、Unsafe、文件/网络 I/O）
                        // 若 WARN_METHODS 已匹配，跳过避免重复告警
                        if (!warned) {
                            for (String prefix : WARN_PREFIXES) {
                                if (fullMethod.startsWith(prefix) || methodPrefix.startsWith(prefix)) {
                                    warnings.add(new Violation(
                                            currentClass,
                                            fullMethod,
                                            ViolationType.WARNING,
                                            "Potentially dangerous API: " + categorize(prefix)));
                                    break;
                                }
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    // ==================== 结果类 ====================

    /**
     * 根据规则前缀查找风险分类
     */
    private static String categorize(String prefix) {
        return PREFIX_CATEGORY.getOrDefault(prefix, "Unknown");
    }

    public enum ViolationType {
        CRITICAL, WARNING
    }

    @Value
    public static class Violation {
        String className;
        String apiCall;
        ViolationType type;
        String message;

        @NonNull
        @Override
        public String toString() {
            return String.format("[%s] %s in %s: %s", type, apiCall, className, message);
        }
    }

    @Value
    public static class ScanResult {
        @NonNull
        List<Violation> errors;
        @NonNull
        List<Violation> warnings;

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
                throw new LingException("Ling security check failed:\n" + msg);
            }
        }

        public void logWarnings() {
            if (hasWarnings()) {
                log.warn("Ling security warnings:");
                warnings.forEach(w -> log.warn("  {}", w));
            }
        }
    }
}