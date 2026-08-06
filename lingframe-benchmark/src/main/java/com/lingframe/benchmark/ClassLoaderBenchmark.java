package com.lingframe.benchmark;

import com.lingframe.core.classloader.LingClassLoader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * ClassLoader 创建、类加载与销毁性能基准测试
 * <p>
 * 测量 LingClassLoader 的生命周期耗时与类加载开销。
 * 本测试已进行生产级改造：
 * 1. 采用显式引入的 ASM 动态在内存中生成 5 个具有深度继承结构（Base → Child1 → ... → Child4）的合法可运行 Class 字节码。
 * 2. 补齐类加载测试（createLoadAndDestroy），分离生命周期固定开销与类加载可变开销。
 * 3. 补充了 4 线程/8 线程的高并发 ClassLoader 实例化与加载测试。
 * <p>
 * 【学术级性能说明：类加载开销减法的近似性】
 * 在报告中，利用 `createLoadAndDestroy - createAndDestroy` 基准值来估算纯类加载（Linking & Verification）的耗时
 * 属于**近似估计，非严格可减**。
 * 因为 JVM 在 loadClass 阶段会触发类加载子系统的全局状态变动（包括方法区 Metaspace 的分配、JIT 编译队列的异步触发），
 * 这导致进行类加载时的 JVM 全局上下文与纯 ClassLoader 实例化的 JVM 上下文存在系统性的非线性差异。
 */
@BenchmarkMode({ Mode.AverageTime, Mode.SampleTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = { "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn" })
@State(Scope.Benchmark)
public class ClassLoaderBenchmark {

    @Param({ "1", "10", "100", "1000" })
    private int jarCount;

    private URL[] jarUrls;
    private File tempDir;

    /** 收集当前线程创建但未关闭 of ClassLoader，每个线程只 close 属于自己的实例 */
    @State(Scope.Thread)
    public static class ThreadLocalLoaderCollector {
        final ConcurrentLinkedQueue<LingClassLoader> loaders = new ConcurrentLinkedQueue<>();

        @TearDown(Level.Invocation)
        public void closeAfterInvocation() {
            LingClassLoader cl = loaders.poll();
            if (cl != null) {
                try {
                    cl.close();
                } catch (Exception ignored) {
                }
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            LingClassLoader cl;
            while ((cl = loaders.poll()) != null) {
                try {
                    cl.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = File.createTempFile("lingframe-bench-", "");
        tempDir.delete();
        tempDir.mkdirs();

        jarUrls = new URL[jarCount];
        for (int i = 0; i < jarCount; i++) {
            File jarFile = new File(tempDir, "bench-ling-" + i + ".jar");
            // 将包含 ASM 类的真实 JAR 放置于 classpath 最末尾，模拟最长检索路径
            if (i == jarCount - 1) {
                createJarWithAsmClasses(jarFile);
            } else {
                createEmptyJar(jarFile);
            }
            jarUrls[i] = jarFile.toURI().toURL();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
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
     * 测量 ClassLoader 的创建与销毁生命周期固定开销（不加载类）
     */
    @Benchmark
    public void createAndDestroy(Blackhole bh) throws Exception {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader());
        bh.consume(cl);
        cl.close();
    }

    /**
     * 仅测量 ClassLoader 实例化开销（由 ThreadLocalLoaderCollector 在 Invocation 结束后异步清理）
     */
    @Benchmark
    public void createOnly(ThreadLocalLoaderCollector collector, Blackhole bh) {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader());
        bh.consume(cl);
        collector.loaders.add(cl);
    }

    /**
     * 测量 ClassLoader 创建 + 加载 5 个类 + 销毁的完整生命周期开销
     * <p>
     * 包含完整的类寻找、字节码链接与校验过程。
     */
    @Benchmark
    public void createLoadAndDestroy(Blackhole bh) throws Exception {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader());
        try {
            // 加载具有继承关系的 5 个类，触发递归解析
            bh.consume(cl.loadClass("com.bench.ChildClass4"));
            bh.consume(cl.loadClass("com.bench.ChildClass3"));
            bh.consume(cl.loadClass("com.bench.ChildClass2"));
            bh.consume(cl.loadClass("com.bench.ChildClass1"));
            bh.consume(cl.loadClass("com.bench.BaseClass"));
        } finally {
            cl.close();
        }
    }

    /**
     * 4 线程并发测量 ClassLoader 生命周期固定开销
     */
    @Benchmark
    @Threads(4)
    public void concurrentCreateAndDestroy_4Threads(Blackhole bh) throws Exception {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader());
        bh.consume(cl);
        cl.close();
    }

    /**
     * 8 线程并发测量 ClassLoader 生命周期固定开销
     */
    @Benchmark
    @Threads(8)
    public void concurrentCreateAndDestroy_8Threads(Blackhole bh) throws Exception {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader());
        bh.consume(cl);
        cl.close();
    }

    /**
     * 8 线程并发测量创建 + 链接加载 5 个类 + 销毁的并发解析吞吐表现
     */
    @Benchmark
    @Threads(8)
    public void concurrentCreateLoadAndDestroy_8Threads(Blackhole bh) throws Exception {
        LingClassLoader cl = new LingClassLoader(
                "bench-ling",
                jarUrls,
                ClassLoader.getSystemClassLoader());
        try {
            bh.consume(cl.loadClass("com.bench.ChildClass4"));
            bh.consume(cl.loadClass("com.bench.ChildClass3"));
            bh.consume(cl.loadClass("com.bench.ChildClass2"));
            bh.consume(cl.loadClass("com.bench.ChildClass1"));
            bh.consume(cl.loadClass("com.bench.BaseClass"));
        } finally {
            cl.close();
        }
    }

    private void createJarWithAsmClasses(File file) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
            // 用 ASM 在内存中生成 5 个具有深度继承结构（Base → Child1 → ... → Child4）的合法 Class
            writeClassToJar(jos, "com/bench/BaseClass", "java/lang/Object");
            writeClassToJar(jos, "com/bench/ChildClass1", "com/bench/BaseClass");
            writeClassToJar(jos, "com/bench/ChildClass2", "com/bench/ChildClass1");
            writeClassToJar(jos, "com/bench/ChildClass3", "com/bench/ChildClass2");
            writeClassToJar(jos, "com/bench/ChildClass4", "com/bench/ChildClass3");
        }
    }

    private void createEmptyJar(File file) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
            // 写入极简的 ZipEntry，保证文件能够被 JarLoader 正常识别为空 JAR 包，不破坏类查找流程
            JarEntry entry = new JarEntry("META-INF/");
            jos.putNextEntry(entry);
            jos.closeEntry();
        }
    }

    private void writeClassToJar(JarOutputStream jos, String internalName, String superInternalName) throws IOException {
        JarEntry entry = new JarEntry(internalName + ".class");
        jos.putNextEntry(entry);
        jos.write(generateClassBytes(internalName, superInternalName));
        jos.closeEntry();
    }

    private byte[] generateClassBytes(String internalClassName, String superInternalClassName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalClassName, null, superInternalClassName, null);

        // 写入一个公开无参构造函数
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0); // this
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternalClassName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
