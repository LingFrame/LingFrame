package com.lingframe.starter.resource;

import com.lingframe.core.classloader.LingClassLoader;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.resource.JdbcDriverUnloadHook;
import com.lingframe.core.resource.JvmShutdownHookUnloadHook;
import com.lingframe.core.resource.ThreadReferenceUnloadHook;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.starter.adapter.SpringLingContainer;
import com.lingframe.starter.web.WebInterfaceManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

/**
 * 终极诊断：带 Web 请求模拟、硬编码灵元 Class 反向搜寻的引用图遍历。
 */
@DisplayName("ClassLoader 泄漏终极诊断")
class ClassLoaderLeakDiagnosticTest {

    private static final String APP_CLASS_NAME = "sample.springling.SampleLingApp";

    @Test
    @DisplayName("深度遍历 JVM 引用图")
    void deepTraversal() throws Exception {
        GenericApplicationContext hostContext = new GenericApplicationContext();
        hostContext.refresh();
        RequestMappingHandlerAdapter hostAdapter = new RequestMappingHandlerAdapter();
        hostAdapter.setApplicationContext(hostContext);
        hostAdapter.setMessageConverters(Collections.singletonList(new StringHttpMessageConverter()));
        hostAdapter.afterPropertiesSet();
        RequestMappingHandlerMapping hostMapping = new RequestMappingHandlerMapping();
        hostMapping.setApplicationContext(hostContext);
        hostMapping.afterPropertiesSet();
        WebInterfaceManager manager = new WebInterfaceManager(null, null, null);
        manager.init(hostMapping, hostAdapter, hostContext);

        Path workspace = Files.createTempDirectory("diag-leak");
        WeakReference<ClassLoader> clRef = runIsolatedCycle(workspace, hostContext, hostAdapter, hostMapping, manager);

        // 诊断时暂时保留线程，用以展示是否是线程所致（GC 循环后再销毁）
        for (int i = 0; i < 50 && clRef.get() != null; i++) {
            System.gc();
            System.runFinalization();
            TimeUnit.MILLISECONDS.sleep(100);
        }

        boolean collected = clRef.get() == null;
        System.err.println("[RESULT] ClassLoader 已被 GC: " + collected);

        if (!collected) {
            ClassLoader leaked = clRef.get();
            if (leaked != null) {
                System.err.println("\n========== 全 JVM 深度引用图遍历 ==========");

                // 1. 遍历所有线程 ThreadLocal
                System.err.println("\n--- [1] 线程 ThreadLocal 深度扫描 ---");
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    scanThreadLocalsDeep(t, leaked);
                }

                // 1.5 扫描所有线程的 contextClassLoader / ThreadGroup / UncaughtExceptionHandler
                System.err.println("\n--- [1.5] 线程 contextClassLoader / 线程组 / 异常处理器扫描 ---");
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    scanThreadContextAndGroupDeep(t, leaked);
                }

                // 2. 获取灵元类列表
                System.err.println("\n--- [2] 仍被加载的灵元类 ---");
                List<Class<?>> lingClasses = getLoadedClasses(leaked);
                for (Class<?> cls : lingClasses) {
                    System.err.println("[LOADED] " + cls.getName());
                }

                // 3. 反向查找宿主大对象中的引用
                System.err.println("\n--- [3] 反向查找宿主对灵元 Class 的引用 ---");
                for (Class<?> lingCls : lingClasses) {
                    System.err.println("  扫描宿主是否持有 Class: " + lingCls.getName());
                    findReferencesTo(manager, lingCls, "manager");
                    findReferencesTo(hostMapping, lingCls, "hostMapping");
                    findReferencesTo(hostAdapter, lingCls, "hostAdapter");
                    findReferencesTo(hostContext, lingCls, "hostContext");
                }

                // 4. Logback Logger 扫描
                System.err.println("\n--- [4] Logback Logger 扫描 ---");
                scanLogbackLoggers(leaked);

                // 5. 遍历 Spring Boot ShutdownHook
                System.err.println("\n--- [5] Spring Boot ShutdownHook ---");
                scanShutdownHook(leaked);

                // 5.5 扫描 Spring 静态缓存
                System.err.println("\n--- [5.5] 扫描静态缓存 ---");
                scanSpringStaticFields(leaked);

                // 6. javac / Lombok 进程级静态缓存扫描
                System.err.println("\n--- [6] javac / Lombok 进程级静态缓存扫描 ---");
                scanJavacAndLombokStaticFields(leaked);
            }
        }

        assertTrue(collected,
                "ClassLoader 应在卸载后被 GC 回收。诊断信息已打印到 stderr（搜索 [LEAK-...] / [DIAG]），"
                        + "若未发现泄漏链说明扫描范围不足，需扩大诊断覆盖面");

        manager.shutdown();
        hostContext.close();
        deleteRecursively(workspace);
    }

    private WeakReference<ClassLoader> runIsolatedCycle(
            Path workspace, GenericApplicationContext hostContext,
            RequestMappingHandlerAdapter hostAdapter,
            RequestMappingHandlerMapping hostMapping,
            WebInterfaceManager manager) throws Exception {

        Path sourceDir = workspace.resolve("src");
        Path classesDir = workspace.resolve("classes");
        compileLingApp(sourceDir, classesDir);

        LingClassLoader lingCL = new LingClassLoader(
                "diag-ling",
                new URL[]{classesDir.toUri().toURL()},
                getClass().getClassLoader());
        WeakReference<ClassLoader> clRef = new WeakReference<>(lingCL);

        List<LingUnloadHook> hooks = Arrays.asList(
                new SpringEcosystemUnloadHook(),
                new StorageCacheUnloadHook(),
                new JdbcDriverUnloadHook(),
                new ThreadReferenceUnloadHook(),
                new JvmShutdownHookUnloadHook());

        Class<?> appClass = lingCL.loadClass(APP_CLASS_NAME);
        SpringApplicationBuilder builder = new SpringApplicationBuilder()
                .resourceLoader(new DefaultResourceLoader(lingCL))
                .sources(appClass)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false);
        SpringLingContainer container = new SpringLingContainer(
                builder, lingCL, manager,
                Collections.emptyList(), Collections.emptyList(),
                hostContext, hooks, "v1");
        DefaultLingContext lingContext = new DefaultLingContext("diag-ling",
                mock(LingRepository.class), mock(LingServiceRegistry.class),
                mock(InvocationPipelineEngine.class), mock(PermissionService.class),
                new EventBus());

        container.start(lingContext);

        // 精确模拟 Web 请求
        String routePath = "/diag-ling/demo/ping";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", routePath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Method getHandlerMethod = findGetHandlerMethod(hostMapping, request);
        HandlerExecutionChain chain = (HandlerExecutionChain)
                org.springframework.util.ReflectionUtils.invokeMethod(getHandlerMethod, hostMapping, request);
        if (chain != null) {
            hostAdapter.handle(request, response, chain.getHandler());
            System.err.println("[DIAG-WEB] 请求成功响应: " + response.getContentAsString());
        } else {
            System.err.println("[DIAG-WEB] 错误：找不到 Web 映射链！");
        }

        container.stop();
        for (LingUnloadHook hook : hooks) {
            hook.cleanup("diag-ling", lingCL);
        }
        lingCL.close();
        return clRef;
    }

    private void scanThreadLocalsDeep(Thread t, ClassLoader target) {
        String[] fieldNames = {"threadLocals", "inheritableThreadLocals"};
        for (String fieldName : fieldNames) {
            try {
                Field tlField = Thread.class.getDeclaredField(fieldName);
                tlField.setAccessible(true);
                Object tlMap = tlField.get(t);
                if (tlMap == null) continue;

                Field tableField = tlMap.getClass().getDeclaredField("table");
                tableField.setAccessible(true);
                Object[] table = (Object[]) tableField.get(tlMap);
                if (table == null) continue;

                for (Object entry : table) {
                    if (entry == null) continue;
                    try {
                        Field vf = entry.getClass().getDeclaredField("value");
                        vf.setAccessible(true);
                        Object value = vf.get(entry);
                        if (value == null) continue;

                        Method getMethod = java.lang.ref.Reference.class.getDeclaredMethod("get");
                        Object tlKey = getMethod.invoke(entry);
                        String keyName = tlKey != null ? tlKey.getClass().getName() : "null(gc'd)";

                        if (value.getClass().getClassLoader() == target) {
                            System.err.println("[LEAK-TL] 线程='" + t.getName()
                                    + "' " + fieldName + " key=" + keyName
                                    + " value=" + value.getClass().getName());
                        }

                        String chain = findDeepReference(value, target, 10, new IdentityHashMap<Object, String>());
                        if (chain != null) {
                            System.err.println("[LEAK-TL-DEEP] 线程='" + t.getName()
                                    + "' " + fieldName + " key=" + keyName
                                    + " 持有链: " + chain);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 扫描线程的 contextClassLoader / ThreadGroup / UncaughtExceptionHandler
     * 是否直接或间接持有目标 ClassLoader。
     * ThreadLocal 扫描不到 contextClassLoader —— 它是 Thread 的普通实例字段，不是 ThreadLocal。
     */
    private void scanThreadContextAndGroupDeep(Thread t, ClassLoader target) {
        // 1) contextClassLoader 直接或间接持有
        try {
            ClassLoader tccl = t.getContextClassLoader();
            if (tccl == target) {
                System.err.println("[LEAK-TCCL] 线程='" + t.getName()
                        + "' getContextClassLoader() 直接持有目标 CL");
            } else if (tccl != null) {
                String chain = findDeepReference(tccl, target, 10, new IdentityHashMap<Object, String>());
                if (chain != null) {
                    System.err.println("[LEAK-TCCL-DEEP] 线程='" + t.getName()
                            + "' contextClassLoader 间接持有链: " + chain);
                }
            }
        } catch (Throwable ignored) {}

        // 2) ThreadGroup 持有链
        try {
            ThreadGroup tg = t.getThreadGroup();
            if (tg != null) {
                String chain = findDeepReference(tg, target, 10, new IdentityHashMap<Object, String>());
                if (chain != null) {
                    System.err.println("[LEAK-TG-DEEP] 线程='" + t.getName()
                            + "' ThreadGroup 间接持有链: " + chain);
                }
            }
        } catch (Throwable ignored) {}

        // 3) UncaughtExceptionHandler 持有链
        try {
            Thread.UncaughtExceptionHandler ueh = t.getUncaughtExceptionHandler();
            if (ueh != null) {
                String chain = findDeepReference(ueh, target, 10, new IdentityHashMap<Object, String>());
                if (chain != null) {
                    System.err.println("[LEAK-UEH-DEEP] 线程='" + t.getName()
                            + "' UncaughtExceptionHandler 间接持有链: " + chain);
                }
            }
        } catch (Throwable ignored) {}
    }

    private String findDeepReference(Object obj, Object target, int depth, Map<Object, String> visited) {
        if (obj == null || depth <= 0) return null;
        if (visited.containsKey(obj)) return null;
        visited.put(obj, "");

        if (obj == target) {
            if (target instanceof ClassLoader) return "== ClassLoader";
            if (target instanceof Class) return "== Class[" + ((Class<?>) target).getName() + "]";
            return "== Target";
        }
        
        if (target instanceof ClassLoader) {
            if (obj instanceof Class<?> && ((Class<?>) obj).getClassLoader() == target)
                return "Class[" + ((Class<?>) obj).getName() + "]";
            if (obj instanceof Method && ((Method) obj).getDeclaringClass().getClassLoader() == target)
                return "Method[" + obj + "]";
        }

        if (obj instanceof Map) {
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (i++ > 300) break;
                String r = findDeepReference(e.getKey(), target, depth - 1, visited);
                if (r != null) return "{key" + i + "}." + r;
                r = findDeepReference(e.getValue(), target, depth - 1, visited);
                if (r != null) return "{val" + i + "}." + r;
            }
            return null;
        }
        if (obj instanceof Collection) {
            int i = 0;
            for (Object el : (Collection<?>) obj) {
                if (i++ > 300) break;
                String r = findDeepReference(el, target, depth - 1, visited);
                if (r != null) return "[" + i + "]." + r;
            }
            return null;
        }
        if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            int len = Array.getLength(obj);
            for (int i = 0; i < Math.min(len, 100); i++) {
                Object el = Array.get(obj, i);
                String r = findDeepReference(el, target, depth - 1, visited);
                if (r != null) return "[" + i + "]." + r;
            }
            return null;
        }

        Class<?> c = obj.getClass();
        String cn = c.getName();
        if (cn.startsWith("java.lang.Class") || cn.startsWith("java.lang.String") || cn.startsWith("java.lang.Integer")
                || cn.startsWith("java.lang.Boolean") || cn.startsWith("java.lang.ThreadLocal")
                || cn.startsWith("java.lang.ref.Reference") || cn.startsWith("java.lang.ref.WeakReference")) {
            return null;
        }

        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    String r = findDeepReference(val, target, depth - 1, visited);
                    if (r != null) return c.getSimpleName() + "." + f.getName() + " -> " + r;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void scanSpringStaticFields(ClassLoader target) {
        String[] classNames = {
                "org.springframework.beans.CachedIntrospectionResults",
                "org.springframework.util.ReflectionUtils",
                "org.springframework.core.annotation.AnnotationUtils",
                "org.springframework.core.annotation.AnnotatedElementUtils",
                "org.springframework.core.annotation.MergedAnnotationsCollection",
                "org.springframework.core.annotation.TypeMappedAnnotations",
                "org.springframework.core.annotation.AnnotationTypeMappings",
                "org.springframework.core.ResolvableType",
                "org.springframework.core.convert.Property",
                "org.springframework.core.io.support.SpringFactoriesLoader",
                "org.springframework.aop.framework.AdvisedSupport",
                "org.springframework.web.context.request.RequestContextHolder",
                "org.springframework.context.i18n.LocaleContextHolder",
                "org.springframework.web.servlet.handler.AbstractHandlerMapping",
                "org.springframework.web.method.HandlerMethod",
                "com.fasterxml.jackson.databind.type.TypeFactory",
                "com.fasterxml.jackson.databind.util.ClassUtil",
                "org.springframework.transaction.support.TransactionSynchronizationManager",
                "java.beans.Introspector",
                "org.springframework.core.BridgeMethodResolver",
                "org.springframework.core.SerializableTypeWrapper"
        };
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className);
                for (Field f : cls.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val == null) continue;
                        String chain = findDeepReference(val, target, 10, new IdentityHashMap<Object, String>());
                        if (chain != null) {
                            System.err.println("[LEAK-STATIC] 类 " + className + "." + f.getName()
                                    + " (" + val.getClass().getSimpleName() + ") 持有链: " + chain);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (ClassNotFoundException ignored) {}
        }
    }

    /**
     * 扫描 com.sun.tools.javac.* 和 lombok.* 已加载类的静态字段，
     * 看是否间接持有目标 ClassLoader。
     * javac 的 Context / JavacProcessingEnvironment 是进程级单例，
     * 编译时创建的 ClassSymbol 会通过 .classLoader / .sourcefile 链回被编译类的 ClassLoader。
     * 用反射读 ClassLoader 私有 classes 字段枚举已加载类（JDK8 是 Vector<Class<?>>）。
     */
    @SuppressWarnings("unchecked")
    private void scanJavacAndLombokStaticFields(ClassLoader target) {
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(systemCl);
            if (classes == null) {
                System.err.println("[JAVAC-SKIP] system ClassLoader.classes 为 null");
                return;
            }
            int scanned = 0;
            for (Class<?> cls : classes) {
                String name = cls.getName();
                if (!name.startsWith("com.sun.tools.javac") && !name.startsWith("lombok")) {
                    continue;
                }
                scanned++;
                for (Field f : cls.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val == null) continue;
                        if (val == target) {
                            System.err.println("[LEAK-JAVAC] " + name + "." + f.getName() + " 直接持有目标 CL");
                            continue;
                        }
                        String chain = findDeepReference(val, target, 8, new IdentityHashMap<Object, String>());
                        if (chain != null) {
                            System.err.println("[LEAK-JAVAC-DEEP] " + name + "." + f.getName() + " 间接持有链: " + chain);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            System.err.println("[JAVAC] 扫描了 " + scanned + " 个 javac/lombok 类的静态字段");
        } catch (Throwable e) {
            System.err.println("[JAVAC-ERROR] 枚举失败: " + e);
        }
    }

    private List<Class<?>> getLoadedClasses(ClassLoader cl) {
        List<Class<?>> classes = new ArrayList<>();
        try {
            classes.add(cl.loadClass("sample.springling.SampleLingApp"));
            classes.add(cl.loadClass("sample.springling.SampleLingApp$DemoController"));
        } catch (Throwable e) {
            System.err.println("[LOADED-ERROR] 加载失败: " + e.getMessage());
        }
        return classes;
    }

    private void findReferencesTo(Object root, Object targetValue, String rootPath) {
        String chain = findDeepReference(root, targetValue, 10, new IdentityHashMap<Object, String>());
        if (chain != null) {
            System.err.println("[LEAK-REF] " + rootPath + " -> " + chain);
        }
    }

    private void scanLogbackLoggers(ClassLoader target) {
        try {
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getILoggerFactory = loggerFactoryClass.getMethod("getILoggerFactory");
            Object factory = getILoggerFactory.invoke(null);
            Method getLoggerList = factory.getClass().getMethod("getLoggerList");
            @SuppressWarnings("unchecked")
            List<Object> loggers = (List<Object>) getLoggerList.invoke(factory);
            for (Object logger : loggers) {
                Method getName = logger.getClass().getMethod("getName");
                String name = (String) getName.invoke(logger);
                String chain = findDeepReference(logger, target, 4, new IdentityHashMap<>());
                if (chain != null) {
                    System.err.println("[LEAK-LOGBACK] Logger '" + name + "' 持有链: " + chain);
                }
            }
        } catch (Throwable e) {
            System.err.println("[LOGBACK] 扫描失败: " + e.getMessage());
        }
    }

    private void scanShutdownHook(ClassLoader target) {
        try {
            Field field = Class.forName("org.springframework.boot.SpringApplication")
                    .getDeclaredField("shutdownHook");
            field.setAccessible(true);
            Object hook = field.get(null);
            String chain = findDeepReference(hook, target, 6, new IdentityHashMap<>());
            if (chain != null) {
                System.err.println("[LEAK-HOOK] ShutdownHook 持有链: " + chain);
            } else {
                System.err.println("[OK] ShutdownHook 未发现泄漏");
            }
        } catch (Throwable e) {
            System.err.println("[HOOK] 扫描失败: " + e.getMessage());
        }
    }

    // ========= 辅助方法 =========

    private Method findGetHandlerMethod(RequestMappingHandlerMapping mapping, Object request) {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> reqIntf = findServletInterface(cl, "HttpServletRequest");
        return org.springframework.util.ReflectionUtils.findMethod(mapping.getClass(), "getHandler", reqIntf);
    }

    private Class<?> findServletInterface(ClassLoader cl, String name) {
        try {
            return Class.forName("jakarta.servlet.http." + name, false, cl);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName("javax.servlet.http." + name, false, cl);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
    }

    /**
     * 用 javac 子进程编译灵元应用，避免进程内 javac 的静态缓存污染测试 JVM 的 ClassLoader 回收判定。
     * 子进程退出即释放所有 javac 进程级状态。
     */
    private void compileLingApp(Path sourceDir, Path classesDir) throws IOException {
        Files.createDirectories(sourceDir.resolve("sample/springling"));
        Files.createDirectories(classesDir);
        Path sourceFile = sourceDir.resolve("sample/springling/SampleLingApp.java");
        String source = "package sample.springling;\n"
                + "import com.lingframe.api.ling.Ling;\n"
                + "import com.lingframe.api.context.LingContext;\n"
                + "import org.springframework.boot.autoconfigure.SpringBootApplication;\n"
                + "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "import org.springframework.web.bind.annotation.RestController;\n"
                + "import org.springframework.context.annotation.Bean;\n"
                + "import java.util.concurrent.Executors;\n"
                + "import java.util.concurrent.ExecutorService;\n"
                + "@SpringBootApplication\n"
                + "public class SampleLingApp implements Ling {\n"
                + "    @Override public void onStart(LingContext ctx) {}\n"
                + "    @Override public void onStop(LingContext ctx) {}\n"
                + "    @Bean(name = \"lingExecutor\")\n"
                + "    public ExecutorService lingExecutor() {\n"
                + "        return Executors.newSingleThreadExecutor();\n"
                + "    }\n"
                + "    @RestController\n"
                + "    public static class DemoController {\n"
                + "        @GetMapping(\"/demo/ping\")\n"
                + "        public String ping() { return \"pong\"; }\n"
                + "    }\n"
                + "}\n";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

        // JDK8: java.home 指向 <JDK>/jre，javac 在 <JDK>/bin/javac；其它 JDK 版本 java.home 直接指向 JDK 根
        String javaHome = System.getProperty("java.home");
        String javac;
        if (new File(javaHome, "bin/javac.exe").exists()) {
            javac = new File(javaHome, "bin/javac.exe").getAbsolutePath();
        } else if (new File(javaHome, "bin/javac").exists()) {
            javac = new File(javaHome, "bin/javac").getAbsolutePath();
        } else {
            // JDK8 的 java.home 是 <JDK>/jre，取父目录拿 JDK 根
            File jdkRoot = new File(javaHome).getParentFile();
            File javacExe = new File(jdkRoot, "bin/javac.exe");
            File javacUnix = new File(jdkRoot, "bin/javac");
            if (javacExe.exists()) {
                javac = javacExe.getAbsolutePath();
            } else if (javacUnix.exists()) {
                javac = javacUnix.getAbsolutePath();
            } else {
                throw new RuntimeException("找不到 javac，java.home=" + javaHome);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(
                javac,
                "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                sourceFile.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        try {
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("子进程 javac 编译失败, exit=" + code + "\n" + out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("子进程 javac 被中断", e);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
