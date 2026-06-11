package com.lingframe.starter.loader;

import com.lingframe.core.exception.LingInstallException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AsmMainClassScanner 单元测试")
public class AsmMainClassScannerTest {

    // 符合要求的主类
    @SpringBootApplication
    public static class ValidApp {
        public static void main(String[] args) {}
    }

    // 缺少注解的类
    public static class NoAnnotationApp {
        public static void main(String[] args) {}
    }

    // 缺少 main 方法的类
    @SpringBootApplication
    public static class NoMainMethodApp {
        public void main(String[] args) {} // 非 static
    }

    // main 方法参数不正确的类
    @SpringBootApplication
    public static class InvalidArgsApp {
        public static void main(String args) {} // 参数不是 String[]
    }

    // main 方法不是 public
    @SpringBootApplication
    public static class NonPublicMainApp {
        static void main(String[] args) {}
    }

    // main 方法没有参数
    @SpringBootApplication
    public static class NoArgsMainApp {
        public static void main() {}
    }

    // main 方法返回值不是 void
    @SpringBootApplication
    public static class NonVoidMainApp {
        public static int main(String[] args) { return 0; }
    }

    @TempDir
    Path tempDir;

    private void copyClass(Class<?> clazz, Path targetDir) throws IOException {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        Path targetPath = targetDir.resolve(resourceName);
        Files.createDirectories(targetPath.getParent());
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(resourceName);
             OutputStream os = Files.newOutputStream(targetPath)) {
            assertNotNull(is, "Class not found: " + resourceName);
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        }
    }

    private File createJar(Class<?> clazz, String yamlContent) throws IOException {
        File jarFile = Files.createTempFile(tempDir, "test-ling", ".jar").toFile();
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile));
             InputStream is = clazz.getClassLoader().getResourceAsStream(resourceName)) {
            
            // 写入 class
            assertNotNull(is, "Class not found: " + resourceName);
            jos.putNextEntry(new JarEntry(resourceName));
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                jos.write(buf, 0, len);
            }
            jos.closeEntry();

            // 写入 yaml（如果提供）
            if (yamlContent != null) {
                jos.putNextEntry(new JarEntry("ling.yml"));
                jos.write(yamlContent.getBytes());
                jos.closeEntry();
            }
        }
        return jarFile;
    }

    @Test
    @DisplayName("扫描目录中的主类测试")
    void testScanDirectory() throws IOException {
        Path scanDir = tempDir.resolve("scan-dir");
        Files.createDirectories(scanDir);

        // 场景 1：目录中没有 class 文件
        assertNull(AsmMainClassScanner.scanMainClass(scanDir.toFile()));

        // 场景 2：只存在不合规的类
        copyClass(NoAnnotationApp.class, scanDir);
        assertNull(AsmMainClassScanner.scanMainClass(scanDir.toFile()));

        // 场景 3：存在合规的类
        copyClass(ValidApp.class, scanDir);
        String mainClass = AsmMainClassScanner.scanMainClass(scanDir.toFile());
        assertNotNull(mainClass);
        assertTrue(mainClass.contains("ValidApp"));

        // 场景 4：包含一个名为 "Invalid.class" 的文件夹，模拟无法读取或锁定导致的 IOException 自旋重试
        Path subDirClass = scanDir.resolve("Invalid.class");
        Files.createDirectories(subDirClass);
        String mainClassAfterDir = AsmMainClassScanner.scanMainClass(scanDir.toFile());
        // 含有合规类应该仍能解析出来
        assertNotNull(mainClassAfterDir);
    }

    @Test
    @DisplayName("扫描 JAR 包中的主类测试")
    void testScanJar() throws IOException {
        // 场景 1：非 JAR 文件
        File txtFile = Files.createTempFile(tempDir, "test", ".txt").toFile();
        assertNull(AsmMainClassScanner.scanMainClass(txtFile));

        // 场景 2：无效的 JAR 文件，格式错误
        File badJar = Files.createTempFile(tempDir, "bad", ".jar").toFile();
        try (FileWriter writer = new FileWriter(badJar)) {
            writer.write("invalid zip contents");
        }
        assertThrows(IOException.class, () -> AsmMainClassScanner.scanMainClass(badJar));

        // 场景 3：只有不合规类的 JAR
        File jarNoAnno = createJar(NoAnnotationApp.class, null);
        assertNull(AsmMainClassScanner.scanMainClass(jarNoAnno));

        // 场景 4：含合规类的 JAR
        File jarValid = createJar(ValidApp.class, null);
        String mainClass = AsmMainClassScanner.scanMainClass(jarValid);
        assertNotNull(mainClass);
        assertTrue(mainClass.contains("ValidApp"));
    }

    @Test
    @DisplayName("主类发现与生命周期验证 discoverMainClass 测试")
    void testDiscoverMainClass() throws IOException {
        ClassLoader classLoader = this.getClass().getClassLoader();

        // 场景 1：通过 ling.yml 显式配置合法的主类
        String yamlContent = "id: test-ling\nversion: 1.0.0\nmainClass: " + ValidApp.class.getName();
        File jarWithYaml = createJar(ValidApp.class, yamlContent);
        String discovered = AsmMainClassScanner.discoverMainClass("test-ling", jarWithYaml, classLoader);
        assertEquals(ValidApp.class.getName(), discovered);

        // 场景 2：未配置 ling.yml，自动通过 ASM 扫描出合规主类
        File jarWithoutYaml = createJar(ValidApp.class, null);
        String discoveredAuto = AsmMainClassScanner.discoverMainClass("test-ling", jarWithoutYaml, classLoader);
        assertEquals(ValidApp.class.getName(), discoveredAuto);

        // 场景 3：ling.yml 显式配置了不存在的主类，验证抛出 LingInstallException
        String yamlBadClass = "id: test-ling\nversion: 1.0.0\nmainClass: com.example.NonExist";
        File jarBadClass = createJar(ValidApp.class, yamlBadClass);
        assertThrows(LingInstallException.class, () -> 
            AsmMainClassScanner.discoverMainClass("test-ling", jarBadClass, classLoader)
        );

        // 场景 4：扫描和显式配置均未发现主类，验证抛出 LingInstallException
        File jarNoClass = createJar(NoAnnotationApp.class, null);
        assertThrows(LingInstallException.class, () -> 
            AsmMainClassScanner.discoverMainClass("test-ling", jarNoClass, classLoader)
        );

        // 场景 5：发现的主类不合规（显式配置了缺少注解的类，或缺少 main 的类）
        String yamlNoAnno = "id: test-ling\nversion: 1.0.0\nmainClass: " + NoAnnotationApp.class.getName();
        File jarNoAnno = createJar(NoAnnotationApp.class, yamlNoAnno);
        assertThrows(LingInstallException.class, () -> 
            AsmMainClassScanner.discoverMainClass("test-ling", jarNoAnno, classLoader)
        );

        String yamlNoMain = "id: test-ling\nversion: 1.0.0\nmainClass: " + NoMainMethodApp.class.getName();
        File jarNoMain = createJar(NoMainMethodApp.class, yamlNoMain);
        assertThrows(LingInstallException.class, () -> 
            AsmMainClassScanner.discoverMainClass("test-ling", jarNoMain, classLoader)
        );
    }

    @Test
    @DisplayName("验证主类 validateMainClass 详细分支测试")
    void testValidateMainClassDetail() {
        ClassLoader classLoader = this.getClass().getClassLoader();

        // 1. 合法主类
        assertTrue(AsmMainClassScanner.validateMainClass(ValidApp.class.getName(), classLoader));
        
        // 2. 缺少注解的类
        assertFalse(AsmMainClassScanner.validateMainClass(NoAnnotationApp.class.getName(), classLoader));

        // 3. 缺少 main 方法的类
        assertFalse(AsmMainClassScanner.validateMainClass(NoMainMethodApp.class.getName(), classLoader));

        // 4. main 方法参数不合法的类
        assertFalse(AsmMainClassScanner.validateMainClass(InvalidArgsApp.class.getName(), classLoader));

        assertFalse(AsmMainClassScanner.validateMainClass(NonPublicMainApp.class.getName(), classLoader));
        assertFalse(AsmMainClassScanner.validateMainClass(NoArgsMainApp.class.getName(), classLoader));
        assertFalse(AsmMainClassScanner.validateMainClass(NonVoidMainApp.class.getName(), classLoader));

        // 5. 不存在的类名，应该捕获 ClassNotFoundException 并返回 false
        assertFalse(AsmMainClassScanner.validateMainClass("com.example.NonExistingClass", classLoader));
    }
}
