package com.lingframe.starter.util;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.core.loader.PluginManifestLoader;
import com.lingframe.core.exception.PluginInstallException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Stream;

/**
 * 使用 ASM 扫描插件主类
 * 精确识别 @SpringBootApplication 注解和 main 方法
 */
@Slf4j
public class AsmMainClassScanner {

    /**
     * 扫描主类（支持目录和 JAR）
     *
     * @param source 插件源文件（目录或 JAR）
     * @return 主类全限定名，未找到返回 null
     */
    public static String scanMainClass(File source) throws IOException {
        if (source.isDirectory()) {
            return scanDirectory(source);
        } else if (source.getName().endsWith(".jar")) {
            return scanJar(source);
        }
        return null;
    }

    /**
     * 扫描 JAR 文件中的主类
     */
    private static String scanJar(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        String className = detectMainClass(entry.getName(), is);
                        if (className != null) {
                            return className;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 扫描目录中的主类
     */
    private static String scanDirectory(File dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            return stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> {
                        try {
                            String relativePath = dir.toPath().relativize(p).toString();
                            try (InputStream is = Files.newInputStream(p)) {
                                String className = detectMainClass(relativePath, is);
                                return className != null;
                            }
                        } catch (IOException e) {
                            log.debug("Failed to read class file: {}", p, e);
                            return false;
                        }
                    })
                    .map(p -> {
                        String rel = dir.toPath().relativize(p).toString();
                        return rel.replace(File.separator, ".").replace(".class", "");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 检测单个类文件是否为主类
     */
    private static String detectMainClass(String relativePath, InputStream is) throws IOException {
        ClassReader reader = new ClassReader(is);
        MainClassDetector detector = new MainClassDetector();
        reader.accept(detector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (detector.isValidMainClass()) {
            // 将路径转换为类名
            return relativePath.replace("/", ".").replace("\\", ".").replace(".class", "");
        }
        return null;
    }

    /**
     * ASM ClassVisitor 用于检测主类
     */
    private static class MainClassDetector extends ClassVisitor {
        private boolean hasSpringBootApplication = false;
        private boolean hasMainMethod = false;

        public MainClassDetector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            // 检查 @SpringBootApplication 注解
            if ("Lorg/springframework/boot/autoconfigure/SpringBootApplication;".equals(descriptor)) {
                hasSpringBootApplication = true;
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            // 检查是否有 main 方法
            if ("main".equals(name) && "([Ljava/lang/String;)V".equals(descriptor)
                    && (access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0) {
                hasMainMethod = true;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        public boolean isValidMainClass() {
            return hasSpringBootApplication && hasMainMethod;
        }
    }

    /**
     * 验证主类有效性（通过反射验证）
     *
     * @param mainClass   主类全限定名
     * @param classLoader 类加载器
     * @return 是否有效
     */
    public static boolean validateMainClass(String mainClass, ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(mainClass);

            // 检查注解
            boolean hasAnnotation = clazz.isAnnotationPresent(SpringBootApplication.class);

            // 检查 main 方法
            boolean hasMainMethod = Arrays.stream(clazz.getMethods())
                    .anyMatch(m -> m.getName().equals("main")
                                   && Modifier.isPublic(m.getModifiers())
                                   && Modifier.isStatic(m.getModifiers())
                                   && m.getParameterCount() == 1
                                   && m.getParameterTypes()[0] == String[].class
                                   && m.getReturnType() == void.class);

            return hasAnnotation && hasMainMethod;
        } catch (ClassNotFoundException e) {
            log.warn("Main class not found: {}", mainClass, e);
            return false;
        }
    }

    /**
     * 发现主类（统一入口）
     *
     * @param pluginId    插件 ID
     * @param source      插件源文件
     * @param classLoader 类加载器
     * @return 主类全限定名
     * @throws IllegalStateException 未找到或验证失败时抛出
     */
    @NonNull
    public static String discoverMainClass(String pluginId, File source,
            ClassLoader classLoader) {
        String mainClass = null;
        PluginDefinition def = PluginManifestLoader.parseDefinition(source);
        if (def != null) {
            mainClass = def.getMainClass();
        } else {
            // 配置未指定，使用 ASM 扫描
            try {
                mainClass = scanMainClass(source);
                if (mainClass != null) {
                    log.debug("Discovered main class for plugin {}: {}", pluginId, mainClass);
                }
            } catch (IOException e) {
                log.error("Failed to scan main class for plugin {}: {}", pluginId, source.getAbsolutePath(), e);
            }
        }

        // 验证主类
        if (mainClass == null) {
            throw new PluginInstallException(
                    pluginId,
                    String.format("Cannot find Main-Class for plugin: %s. " +
                            "Please specify 'mainClass' in plugin.yml or ensure @SpringBootApplication annotation is present.",
                            pluginId));
        }

        if (!validateMainClass(mainClass, classLoader)) {
            throw new PluginInstallException(
                    pluginId,
                    String.format("Invalid main class for plugin %s: %s. " +
                            "Must have @SpringBootApplication annotation and public static void main(String[] args) method.",
                            pluginId, mainClass));
        }

        return mainClass;
    }
}