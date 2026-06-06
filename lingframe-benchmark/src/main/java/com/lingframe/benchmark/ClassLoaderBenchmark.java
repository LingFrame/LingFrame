package com.lingframe.benchmark;

import com.lingframe.core.classloader.LingClassLoader;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * ClassLoader 创建与销毁性能基准测试
 * <p>
 * 测量 LingClassLoader 的创建、类加载和关闭的端到端耗时，
 * 为热部署/热卸载场景提供性能基线。
 *
 * <p>运行方式：
 * <pre>
 * mvn -pl lingframe-benchmark package -DskipTests
 * java -jar lingframe-benchmark/target/lingframe-benchmarks.jar ClassLoaderBenchmark
 * </pre>
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class ClassLoaderBenchmark {

    @Param({"1", "5", "10"})
    private int jarCount;

    private URL[] jarUrls;
    private File tempDir;

    /** 收集 createOnly 创建但未关闭的 ClassLoader，在 TearDown 中统一关闭 */
    private final ConcurrentLinkedQueue<LingClassLoader> unclosedLoaders = new ConcurrentLinkedQueue<LingClassLoader>();

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = File.createTempFile("lingframe-bench-", "");
        tempDir.delete();
        tempDir.mkdirs();

        jarUrls = new URL[jarCount];
        for (int i = 0; i < jarCount; i++) {
            File jarFile = new File(tempDir, "bench-ling-" + i + ".jar");
            createMinimalJar(jarFile, i);
            jarUrls[i] = jarFile.toURI().toURL();
        }
    }

    /**
     * 每次调用后关闭 ClassLoader，防止文件描述符累积耗尽。
     * <p>
     * LingClassLoader extends URLClassLoader，每个实例会打开新的 ZipFile 句柄。
     * 若不逐次关闭，多次迭代后文件描述符将超出 OS 限制。
     * JMH 保证 @TearDown(Level.Invocation) 在测量时间窗口之外执行。
     */
    @TearDown(Level.Invocation)
    public void closeAfterInvocation() {
        LingClassLoader cl = unclosedLoaders.poll();
        if (cl != null) {
            try {
                cl.close();
            } catch (Exception ignored) {
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // 关闭 createOnly 创建但未关闭的 ClassLoader，避免资源泄漏
        LingClassLoader cl;
        while ((cl = unclosedLoaders.poll()) != null) {
            try {
                cl.close();
            } catch (Exception ignored) {
            }
        }

        if (tempDir != null) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            tempDir.delete();
        }
    }

    /**
     * 测量 LingClassLoader 的创建 + 关闭全链路耗时
     * <p>
     * 模拟一次灵元部署然后立即卸载的 ClassLoader 层面开销。
     */
    @Benchmark
    public void createAndDestroy(Blackhole bh) throws Exception {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader()
        );
        bh.consume(cl);
        cl.close();
    }

    /**
     * 仅测量 LingClassLoader 创建耗时（不关闭）
     * <p>
     * 创建的 ClassLoader 收集到 unclosedLoaders，在 @TearDown 中统一关闭。
     */
    @Benchmark
    public void createOnly(Blackhole bh) {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader()
        );
        bh.consume(cl);
        unclosedLoaders.add(cl);
    }

    private void createMinimalJar(File file, int index) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
            // 写入一个最小的 .class 占位文件
            JarEntry entry = new JarEntry("com/bench/Placeholder" + index + ".class");
            jos.putNextEntry(entry);
            // 写入最小的合法 class 文件头（magic number + version）
            // 这不需要是有效的 class 文件，只要 JAR 结构正确即可
            jos.write(new byte[]{
                    (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, // magic
                    0x00, 0x00, 0x00, 0x3D, // version (Java 17 = 61)
                    0x00, 0x03, // constant pool count
                    0x07, 0x00, 0x02, // CONSTANT_Class
                    0x01, 0x00, 0x1E, // CONSTANT_Utf8
                    'c', 'o', 'm', '/', 'b', 'e', 'n', 'c', 'h', '/',
                    'P', 'l', 'a', 'c', 'e', 'h', 'o', 'l', 'd', 'e',
                    'r', (byte) ('0' + index),
                    0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00
            });
            jos.closeEntry();
        }
    }
}
