package com.lingframe.starter.resource;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * 单元测试专用的 ClassLoader 强引用泄漏辅助诊断工具。
 */
public final class ClassLoaderLeakDiagnoser {

    private static final List<String> logLines = new ArrayList<>();

    private ClassLoaderLeakDiagnoser() {}

    private static void writeLog(String message) {
        System.err.println(message);
        logLines.add(message);
    }

    private static void flushLogs() {
        try {
            java.io.File targetDir = new java.io.File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            try (PrintWriter writer = new PrintWriter(new FileWriter("target/diagnose_output.txt", true))) {
                writer.println("--- DIAGNOSE ROUND: " + new Date() + " ---");
                for (String line : logLines) {
                    writer.println(line);
                }
                writer.println();
            }
        } catch (Exception e) {
            System.err.println("[DIAG-ERR] Failed to write diagnose_output.txt: " + e.getMessage());
        } finally {
            logLines.clear();
        }
    }

    /**
     * 泄漏诊断的总入口。
     */
    public static void diagnoseClassLoaderLeak(ClassLoader leakedLoader, Object coreContext, Object testLingCore) {
        if (leakedLoader == null) {
            return;
        }
        writeLog("[DIAG] ==================== 开始 ClassLoader 强引用泄漏分析 ====================");
        writeLog("[DIAG] 泄漏 ClassLoader: " + leakedLoader);

        Set<String> leakedClassNames = new HashSet<>();
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(leakedLoader);
            if (classes != null) {
                for (Class<?> c : classes) {
                    leakedClassNames.add(c.getName());
                }
            }
        } catch (Exception ignored) {}

        // 1. 扫描灵核 Spring 容器的单例 Bean 实例
        diagnoseApplicationContext(coreContext, leakedLoader, leakedClassNames);

        // 1.2 扫描灵核 TestLingCore 实例字段 (极重要，因为 HandlerMapping/Adapter 是 new 出来的非 Bean 实例)
        if (testLingCore != null) {
            writeLog("[DIAG-CORE] 开始扫描 TestLingCore 实例字段...");
            checkObjectForLeak(testLingCore, "TestLingCore", leakedLoader, leakedClassNames, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }

        // 1.5 扫描 java.io.ObjectStreamClass 静态缓存 (Bootstrap 级别)
        diagnoseObjectStreamClass(leakedLoader);

        // 2. 扫描线程 ContextClassLoader 泄漏
        String tclRef = findThreadContextClassLoaderReference(leakedLoader);
        if (tclRef != null) {
            writeLog("[DIAG] 发现 Thread.contextClassLoader 残留: " + tclRef);
        }

        // 3. 扫描线程 target 字段中的泄漏
        diagnoseThreadTargetField(leakedLoader);

        // 4. 扫描 ThreadLocal 泄漏
        diagnoseThreadLocalsForLeak(leakedLoader);

        // 5. 扫描 JDK Proxy Cache 泄漏
        diagnoseJdkProxyCache(leakedLoader);

        // 6. 扫描 Proxy 实例静态引用
        diagnoseProxyReferences(leakedLoader);

        // 7. 扫描 Tomcat 缓存
        diagnoseTomcatCaches(leakedLoader);

        // 8. 扫描 Spring Boot AutoConfig 缓存
        diagnoseSpringBootAutoConfigCaches(leakedLoader);

        // 9. 扫描 Validation 静态缓存
        checkValidationStaticCache(leakedLoader);

        // 10. 全局静态字段深度扫描
        diagnoseGlobalStaticFields(leakedLoader, leakedClassNames);

        // 11. 诊断存活 Class 的引用链
        diagnoseAliveClasses(leakedLoader);

        // 12. 执行主动清理实验以辅助诊断
        diagnoseActiveCleanup(leakedLoader);

        writeLog("[DIAG] ==================== ClassLoader 强引用泄漏分析结束 ====================");
        
        flushLogs();
    }

    private static void diagnoseObjectStreamClass(ClassLoader leakedLoader) {
        writeLog("[DIAG-OSC] 开始扫描 java.io.ObjectStreamClass 静态缓存...");
        try {
            Class<?> oscClass = Class.forName("java.io.ObjectStreamClass");
            Class<?> cachesClass = null;
            for (Class<?> c : oscClass.getDeclaredClasses()) {
                if (c.getSimpleName().equals("Caches")) {
                    cachesClass = c;
                    break;
                }
            }
            if (cachesClass != null) {
                Field localDescsField = cachesClass.getDeclaredField("localDescs");
                localDescsField.setAccessible(true);
                Map<?, ?> localDescs = (Map<?, ?>) localDescsField.get(null);
                writeLog("[DIAG-OSC] ObjectStreamClass.Caches.localDescs 大小: " + (localDescs != null ? localDescs.size() : "null"));
                if (localDescs != null) {
                    for (Map.Entry<?, ?> entry : localDescs.entrySet()) {
                        Object key = entry.getKey();
                        Class<?> targetClass = null;
                        if (key instanceof Reference) {
                            targetClass = (Class<?>) ((Reference<?>) key).get();
                        }
                        if (targetClass != null && targetClass.getClassLoader() == leakedLoader) {
                            writeLog("[DIAG-OSC] ⚠️ 发现泄漏 Class 存在于 ObjectStreamClass.Caches.localDescs: " + targetClass.getName());
                        }
                    }
                }

                Field reflectorsField = cachesClass.getDeclaredField("reflectors");
                reflectorsField.setAccessible(true);
                Map<?, ?> reflectors = (Map<?, ?>) reflectorsField.get(null);
                writeLog("[DIAG-OSC] ObjectStreamClass.Caches.reflectors 大小: " + (reflectors != null ? reflectors.size() : "null"));
                if (reflectors != null) {
                    for (Map.Entry<?, ?> entry : reflectors.entrySet()) {
                        Object key = entry.getKey();
                        if (key != null) {
                            Field clField = findFieldInHierarchy(key.getClass(), "cl");
                            if (clField != null) {
                                clField.setAccessible(true);
                                Class<?> targetClass = (Class<?>) clField.get(key);
                                if (targetClass != null && targetClass.getClassLoader() == leakedLoader) {
                                    writeLog("[DIAG-OSC] ⚠️ 发现泄漏 Class 存在于 ObjectStreamClass.Caches.reflectors: " + targetClass.getName());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            writeLog("[DIAG-OSC] 扫描失败: " + e.getMessage());
        }
    }

    private static void diagnoseApplicationContext(Object context, ClassLoader leakedLoader, Set<String> leakedClassNames) {
        if (context == null) {
            writeLog("[DIAG-APPCTX] 传入的 ApplicationContext 为 null，跳过 Bean 扫描");
            return;
        }
        writeLog("[DIAG-APPCTX] 开始扫描 ApplicationContext 实例中的所有单例 Bean... TargetLoader=" + leakedLoader);
        try {
            Method getBeanFactoryMethod = context.getClass().getMethod("getBeanFactory");
            Object beanFactory = getBeanFactoryMethod.invoke(context);
            
            Field singletonObjectsField = findFieldInHierarchy(beanFactory.getClass(), "singletonObjects");
            if (singletonObjectsField != null) {
                singletonObjectsField.setAccessible(true);
                Map<?, ?> singletonObjects = (Map<?, ?>) singletonObjectsField.get(beanFactory);
                writeLog("[DIAG-APPCTX] 找到单例 Bean 个数: " + singletonObjects.size());
                
                Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Map.Entry<?, ?> entry : singletonObjects.entrySet()) {
                    String beanName = (String) entry.getKey();
                    Object beanInstance = entry.getValue();
                    if (beanInstance != null) {
                        checkObjectForLeak(beanInstance, "SpringBean[" + beanName + "]", leakedLoader, leakedClassNames, visited, 0);
                    }
                }
            }
        } catch (Exception e) {
            writeLog("[DIAG-APPCTX] 扫描失败: " + e.getMessage());
        }
    }

    private static String findThreadContextClassLoaderReference(ClassLoader targetClassLoader) {
        if (targetClassLoader == null) {
            return null;
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != null && thread.getContextClassLoader() == targetClassLoader) {
                return "thread.contextClassLoader:" + thread.getName();
            }
        }
        return null;
    }

    private static void diagnoseThreadTargetField(ClassLoader leakedLoader) {
        try {
            Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            int foundCount = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t == null) continue;
                try {
                    Object target = targetField.get(t);
                    if (target != null && target.getClass().getClassLoader() == leakedLoader) {
                        foundCount++;
                        writeLog("[DIAG-THREAD] Thread[" + t.getName() + "].target -> " 
                                + target.getClass().getName() + " (loaded by leaked CL)");
                    }
                } catch (Exception ignored) {}
            }
            if (foundCount == 0) {
                writeLog("[DIAG-THREAD] 未在线程 target 字段中找到关联引用");
            } else {
                writeLog("[DIAG-THREAD] 共找到 " + foundCount + " 个关联引用");
            }
        } catch (Exception e) {
            writeLog("[DIAG-THREAD] 扫描失败: " + e.getMessage());
        }
    }

    private static void diagnoseThreadLocalsForLeak(ClassLoader leakedLoader) {
        try {
            Field tlField = Thread.class.getDeclaredField("threadLocals");
            tlField.setAccessible(true);
            Field itlField = Thread.class.getDeclaredField("inheritableThreadLocals");
            itlField.setAccessible(true);
            Field tableField = null;
            Field valueField = null;

            int foundCount = 0;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t == null) continue;
                Object tlMap = tlField.get(t);
                if (tlMap != null) {
                    if (tableField == null) {
                        tableField = tlMap.getClass().getDeclaredField("table");
                        tableField.setAccessible(true);
                    }
                    Object[] table = (Object[]) tableField.get(tlMap);
                    if (table != null) {
                        for (Object entry : table) {
                            if (entry == null) continue;
                            Reference<?> ref = (Reference<?>) entry;
                            Object key = ref.get();
                            if (valueField == null) {
                                valueField = entry.getClass().getDeclaredField("value");
                                valueField.setAccessible(true);
                            }
                            Object val = valueField.get(entry);
                            boolean keyRelated = key != null && key.getClass().getClassLoader() == leakedLoader;
                            boolean valRelated = val != null && val.getClass().getClassLoader() == leakedLoader;
                            boolean valCL = false;
                            if (!valRelated && val instanceof Class) {
                                valCL = ((Class<?>) val).getClassLoader() == leakedLoader;
                            }
                            if (keyRelated || valRelated || valCL) {
                                foundCount++;
                                String keyInfo = key == null ? "null" : key.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(key));
                                String valInfo = val == null ? "null" : (val instanceof Class ? "Class[" + ((Class<?>) val).getName() + "]" : val.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(val)));
                                writeLog("[DIAG-TL] Thread[" + t.getName() + "].threadLocals -> key=" + keyInfo + ", val=" + valInfo);
                            }
                        }
                    }
                }
                Object itlMap = itlField.get(t);
                if (itlMap != null) {
                    if (tableField == null) {
                        tableField = itlMap.getClass().getDeclaredField("table");
                        tableField.setAccessible(true);
                    }
                    Object[] table = (Object[]) tableField.get(itlMap);
                    if (table != null) {
                        for (Object entry : table) {
                            if (entry == null) continue;
                            Reference<?> ref = (Reference<?>) entry;
                            Object key = ref.get();
                            if (valueField == null) {
                                valueField = entry.getClass().getDeclaredField("value");
                                valueField.setAccessible(true);
                            }
                            Object val = valueField.get(entry);
                            boolean keyRelated = key != null && key.getClass().getClassLoader() == leakedLoader;
                            boolean valRelated = val != null && val.getClass().getClassLoader() == leakedLoader;
                            if (keyRelated || valRelated) {
                                foundCount++;
                                writeLog("[DIAG-ITL] Thread[" + t.getName() + "].inheritableThreadLocals -> key=" 
                                        + (key != null ? key.getClass().getName() : "null") + ", val=" + (val != null ? val.getClass().getName() : "null"));
                            }
                        }
                    }
                }
            }
            if (foundCount == 0) {
                writeLog("[DIAG-TL] 未在线程 ThreadLocal 表中找到关联条目");
            }
        } catch (Exception e) {
            writeLog("[DIAG-TL] 扫描失败: " + e.getMessage());
        }
    }

    private static void diagnoseJdkProxyCache(ClassLoader leakedLoader) {
        try {
            Field cacheField = Proxy.class.getDeclaredField("proxyClassCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache == null) {
                writeLog("[DIAG-PROXY] Proxy.proxyClassCache 为 null");
                return;
            }
            Field mapField = findFieldInHierarchy(cache.getClass(), "map");
            if (mapField != null) {
                mapField.setAccessible(true);
                Object map = mapField.get(cache);
                if (map instanceof ConcurrentMap) {
                    ConcurrentMap<?, ?> cmap = (ConcurrentMap<?, ?>) map;
                    for (Object key : cmap.keySet()) {
                        if (key instanceof Reference) {
                            Object referent = ((Reference<?>) key).get();
                            if (referent == leakedLoader) {
                                writeLog("[DIAG-PROXY] ⚠️ 找到 leakedLoader 作为 WeakCache key！");
                            }
                        }
                    }
                }
            }
        } catch (NoSuchFieldException e) {
            writeLog("[DIAG-PROXY] proxyClassCache 字段不存在");
        } catch (Exception e) {
            writeLog("[DIAG-PROXY] 扫描失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void diagnoseProxyReferences(ClassLoader leakedLoader) {
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(leakedLoader);
            if (classes == null) return;

            Set<String> proxyClassNames = new LinkedHashSet<>();
            for (Class<?> c : classes) {
                if (c.getName().startsWith("com.sun.proxy.$Proxy")) {
                    proxyClassNames.add(c.getName());
                }
            }
            if (proxyClassNames.isEmpty()) {
                return;
            }

            int found = 0;
            for (Class<?> cls : findAllLoadedLingFrameClasses()) {
                Field[] fields;
                try {
                    fields = cls.getDeclaredFields();
                } catch (Throwable ignored) {
                    continue;
                }
                for (Field f : fields) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val == null) continue;
                        String valClassName = val.getClass().getName();
                        if (proxyClassNames.contains(valClassName)) {
                            found++;
                            writeLog("[DIAG-PROXY-REF] ⚠️ " + cls.getName() + "." + f.getName() + " -> " + valClassName);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Exception e) {
            writeLog("[DIAG-PROXY-REF] 扫描失败: " + e.getMessage());
        }
    }

    private static void diagnoseTomcatCaches(ClassLoader leakedLoader) {
        String[] tomcatClasses = {
                "org.apache.catalina.loader.WebappClassLoaderBase",
                "org.apache.catalina.startup.Tomcat",
                "org.apache.catalina.core.StandardContext",
                "org.apache.coyote.AbstractProtocol",
                "org.apache.tomcat.util.net.NioEndpoint",
        };
        for (String className : tomcatClasses) {
            try {
                Class<?> cls = Class.forName(className);
                for (Field f : cls.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null) continue;
                    if (isRelatedToClassLoader(val, leakedLoader)) {
                        writeLog("[DIAG-TOMCAT] " + className + "." + f.getName() + " -> " + val.getClass().getName());
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable ignored) {}
        }
    }

    private static void diagnoseSpringBootAutoConfigCaches(ClassLoader leakedLoader) {
        String[] bootClasses = {
                "org.springframework.boot.autoconfigure.AutoConfigurationImportSelector",
                "org.springframework.boot.autoconfigure.AutoConfigurationPackages",
                "org.springframework.boot.autoconfigure.condition.OnBeanCondition",
                "org.springframework.boot.autoconfigure.condition.OnClassCondition",
                "org.springframework.boot.context.properties.bind.Binder",
        };
        for (String className : bootClasses) {
            try {
                Class<?> cls = Class.forName(className);
                for (Field f : cls.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val == null) continue;
                    if (isRelatedToClassLoader(val, leakedLoader)) {
                        writeLog("[DIAG-BOOT] " + className + "." + f.getName() + " -> " + val.getClass().getName());
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable ignored) {}
        }
    }

    private static void checkValidationStaticCache(ClassLoader leakedLoader) {
        try {
            Class<?> validationClass = Class.forName("javax.validation.Validation");
            for (Field f : validationClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        checkObjectForClassLoader(val, leakedLoader, "javax.validation.Validation." + f.getName(), new IdentityHashMap<>(), 0);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable e) {
            writeLog("[DIAG-VAL] 检查失败: " + e.getMessage());
        }
    }

    private static void checkObjectForClassLoader(Object obj, ClassLoader leakedLoader, String path, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (obj == null || depth > 12) return;
        if (visited.put(obj, Boolean.TRUE) != null) return;

        if (obj == leakedLoader) {
            writeLog("[DIAG-VAL] ⚠️ " + path + " -> ClassLoader 直接引用!");
            return;
        }
        if (obj instanceof Class<?> && ((Class<?>) obj).getClassLoader() == leakedLoader) {
            writeLog("[DIAG-VAL] ⚠️ " + path + " -> Class[" + ((Class<?>) obj).getName() + "] (loaded by leaked CL)");
            return;
        }
        if (obj.getClass().getClassLoader() == leakedLoader) {
            writeLog("[DIAG-VAL] ⚠️ " + path + " -> instance of " + obj.getClass().getName());
            return;
        }

        if (obj instanceof Map) {
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (i++ > 20) break;
                checkObjectForClassLoader(e.getKey(), leakedLoader, path + "{key" + i + "}", visited, depth + 1);
                checkObjectForClassLoader(e.getValue(), leakedLoader, path + "{val" + i + "}", visited, depth + 1);
            }
        } else if (obj instanceof Collection) {
            int i = 0;
            for (Object el : (Collection<?>) obj) {
                if (i++ > 20) break;
                checkObjectForClassLoader(el, leakedLoader, path + "[" + i + "]", visited, depth + 1);
            }
        } else {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val != null) {
                            checkObjectForClassLoader(val, leakedLoader, path + "." + f.getName(), visited, depth + 1);
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        }
    }

    private static void diagnoseGlobalStaticFields(ClassLoader leakedLoader, Set<String> leakedClassNames) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        ClassLoader cur = cl;
        while (cur != null) {
            try {
                Field classesField = ClassLoader.class.getDeclaredField("classes");
                classesField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(cur);
                if (classes != null) {
                    for (Class<?> cls : classes) {
                        Field[] fields;
                        try {
                            fields = cls.getDeclaredFields();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        for (Field f : fields) {
                            if (!Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object val = f.get(null);
                                if (val != null) {
                                    checkObjectForLeak(val, cls.getName() + "." + f.getName(), leakedLoader, leakedClassNames, new HashSet<>(), 0);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
            cur = cur.getParent();
        }
    }

    private static void checkObjectForLeak(Object obj, String path, ClassLoader leakedLoader, Set<String> leakedClassNames, Set<Object> visited, int depth) {
        if (obj == null || depth > 12 || visited.contains(obj)) return;
        visited.add(obj);

        if (obj == leakedLoader) {
            writeLog("[DIAG-GLOBAL] ⚠️ " + path + " -> ClassLoader 直接引用！");
            return;
        }
        if (obj instanceof ClassLoader) return;

        String objClassName = obj.getClass().getName();
        if (leakedClassNames.contains(objClassName)) {
            writeLog("[DIAG-GLOBAL] ⚠️ " + path + " -> " + objClassName + " (泄漏类实例)");
            return;
        }
        if (obj instanceof Class<?> && leakedClassNames.contains(((Class<?>) obj).getName())) {
            writeLog("[DIAG-GLOBAL] ⚠️ " + path + " -> Class[" + ((Class<?>) obj).getName() + "] (泄漏 Class 对象)");
            return;
        }

        if (obj instanceof Map) {
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (i++ > 50) break;
                checkObjectForLeak(e.getKey(), path + "{key" + i + "}", leakedLoader, leakedClassNames, visited, depth + 1);
                checkObjectForLeak(e.getValue(), path + "{val" + i + "}", leakedLoader, leakedClassNames, visited, depth + 1);
            }
        } else if (obj instanceof Collection) {
            int i = 0;
            for (Object el : (Collection<?>) obj) {
                if (i++ > 50) break;
                checkObjectForLeak(el, path + "[" + i + "]", leakedLoader, leakedClassNames, visited, depth + 1);
            }
        } else if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            int len = Math.min(Array.getLength(obj), 50);
            for (int i = 0; i < len; i++) {
                checkObjectForLeak(Array.get(obj, i), path + "[" + i + "]", leakedLoader, leakedClassNames, visited, depth + 1);
            }
        } else {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val != null) {
                            checkObjectForLeak(val, path + "." + f.getName(), leakedLoader, leakedClassNames, visited, depth + 1);
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void diagnoseAliveClasses(ClassLoader leakedLoader) {
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(leakedLoader);
            if (classes == null || classes.isEmpty()) {
                return;
            }
            writeLog("[DIAG-CLASSES] ClassLoader 共加载了 " + classes.size() + " 个类:");
            for (Class<?> c : classes) {
                WeakReference<Class<?>> ref = new WeakReference<>(c);
                System.gc();
                System.runFinalization();
                boolean alive = ref.get() != null;
                writeLog("[DIAG-CLASSES] " + (alive ? "ALIVE" : "DEAD") + " | " + c.getName());
                if (alive) {
                    traceClassReferences(c, 0);
                    dumpHeap(c.getName());
                }
            }
        } catch (Exception ignored) {}
    }

    private static void traceClassReferences(Class<?> target, int indent) {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread t = entry.getKey();
                if (t == null) continue;
                try {
                    Field tlField = Thread.class.getDeclaredField("threadLocals");
                    tlField.setAccessible(true);
                    Object tlMap = tlField.get(t);
                    if (tlMap != null) {
                        Field tableField = tlMap.getClass().getDeclaredField("table");
                        tableField.setAccessible(true);
                        Object[] table = (Object[]) tableField.get(tlMap);
                        if (table != null) {
                            for (Object tlEntry : table) {
                                if (tlEntry == null) continue;
                                Reference<?> ref = (Reference<?>) tlEntry;
                                Object key = ref.get();
                                Field valueField = tlEntry.getClass().getDeclaredField("value");
                                valueField.setAccessible(true);
                                Object val = valueField.get(tlEntry);
                                if (val == target || (val instanceof Class && ((Class<?>) val).getName().equals(target.getName()))) {
                                    writeLog("  [DIAG-TRACE] Thread[" + t.getName() + "].threadLocals -> key=" 
                                            + (key != null ? key.getClass().getName() : "null") + ", value=" + target.getName());
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void diagnoseActiveCleanup(ClassLoader leakedLoader) {
        writeLog("[DIAG-CLEANUP] === 主动清理实验开始 ===");
        Set<String> savedLeakedClassNames = new HashSet<>();
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(leakedLoader);
            if (classes != null) {
                for (Class<?> c : classes) {
                    savedLeakedClassNames.add(c.getName());
                }
                classes.clear();
            }
        } catch (Exception ignored) {}

        ClassLoader holder = leakedLoader;
        leakedLoader = null;

        for (int i = 0; i < 10; i++) {
            System.gc();
            System.runFinalization();
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }

        WeakReference<ClassLoader> ref = new WeakReference<>(holder);
        holder = null;
        System.gc();
        System.runFinalization();
        if (ref.get() == null) {
            writeLog("[DIAG-CLEANUP] ✅ 主动清理后 ClassLoader 可被 GC 回收！");
        } else {
            writeLog("[DIAG-CLEANUP] ❌ 主动清理后 ClassLoader 仍不可被 GC 回收");
            // classes 向量可能已被清空，diagnoseAliveSteps 不会触发 dumpHeap；
            // 此处主动 dump 一份，供 jhat/OQL 分析 GC root 引用链
            dumpHeap("cleanup-failed-" + Integer.toHexString(System.identityHashCode(ref.get())));
            diagnosePostCleanupLeak(ref.get(), savedLeakedClassNames);
        }
        writeLog("[DIAG-CLEANUP] === 主动清理实验结束 ===");
    }

    @SuppressWarnings("unchecked")
    private static void diagnosePostCleanupLeak(ClassLoader leakedLoader, Set<String> preSavedLeakedClassNames) {
        if (leakedLoader == null) return;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int found = 0;
        ClassLoader cur = Thread.currentThread().getContextClassLoader();
        while (cur != null) {
            try {
                Field classesField = ClassLoader.class.getDeclaredField("classes");
                classesField.setAccessible(true);
                Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(cur);
                if (classes != null) {
                    for (Class<?> cls : classes) {
                        Field[] fields;
                        try {
                            fields = cls.getDeclaredFields();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        for (Field f : fields) {
                            if (!Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object val = f.get(null);
                                if (val == null) continue;
                                if (visited.contains(val)) continue;
                                visited.add(val);
                                if (val == leakedLoader) {
                                    found++;
                                    writeLog("[DIAG-POST] ⚠️ 直接引用: " + cls.getName() + "." + f.getName());
                                } else if (val instanceof Class<?> && ((Class<?>) val).getClassLoader() == leakedLoader) {
                                    found++;
                                    writeLog("[DIAG-POST] ⚠️ Class对象引用: " + cls.getName() + "." + f.getName() + " -> " + ((Class<?>) val).getName());
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
            cur = cur.getParent();
        }
        diagnosePostCleanupLeakInstances(leakedLoader, preSavedLeakedClassNames);
    }

    private static void diagnosePostCleanupLeakInstances(ClassLoader leakedLoader, Set<String> preSavedLeakedClassNames) {
        Set<String> leakedClassNames = preSavedLeakedClassNames != null ? new HashSet<>(preSavedLeakedClassNames) : new HashSet<>();
        Set<Class<?>> leakedClasses = new HashSet<>();
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(leakedLoader);
            if (classes != null) {
                for (Class<?> c : classes) {
                    leakedClassNames.add(c.getName());
                    leakedClasses.add(c);
                }
            }
        } catch (Exception ignored) {}

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ClassLoader cur = Thread.currentThread().getContextClassLoader();
        while (cur != null) {
            try {
                Field classesField = ClassLoader.class.getDeclaredField("classes");
                classesField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Vector<Class<?>> allClasses = (Vector<Class<?>>) classesField.get(cur);
                if (allClasses != null) {
                    for (Class<?> cls : allClasses) {
                        Field[] fields;
                        try {
                            fields = cls.getDeclaredFields();
                        } catch (Throwable ignored) {
                            continue;
                        }
                        for (Field f : fields) {
                            if (!Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object val = f.get(null);
                                if (val != null && !visited.contains(val)) {
                                    visited.add(val);
                                    checkInstanceForLeakedClass(val, cls.getName() + "." + f.getName(), leakedClassNames, leakedClasses, leakedLoader, new HashSet<>(), 0);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
            cur = cur.getParent();
        }
    }

    private static int checkInstanceForLeakedClass(Object obj, String path, Set<String> leakedClassNames, Set<Class<?>> leakedClasses, ClassLoader leakedLoader, Set<Object> visited, int depth) {
        if (obj == null || depth > 12) return 0;
        if (!visited.add(obj)) return 0;

        int found = 0;
        String objClassName = obj.getClass().getName();
        if (leakedClassNames.contains(objClassName)) {
            writeLog("[DIAG-INST] ⚠️ " + path + " -> " + objClassName + " (instance loaded by leaked CL)");
            return 1;
        }

        if (obj instanceof Map) {
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                if (i++ > 50) break;
                found += checkInstanceForLeakedClass(e.getKey(), path + "{key" + i + "}", leakedClassNames, leakedClasses, leakedLoader, visited, depth + 1);
                found += checkInstanceForLeakedClass(e.getValue(), path + "{val" + i + "}", leakedClassNames, leakedClasses, leakedLoader, visited, depth + 1);
            }
        } else if (obj instanceof Collection) {
            int i = 0;
            for (Object el : (Collection<?>) obj) {
                if (i++ > 50) break;
                found += checkInstanceForLeakedClass(el, path + "[" + i + "]", leakedClassNames, leakedClasses, leakedLoader, visited, depth + 1);
            }
        } else {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val != null) {
                            found += checkInstanceForLeakedClass(val, path + "." + f.getName(), leakedClassNames, leakedClasses, leakedLoader, visited, depth + 1);
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        }
        return found;
    }

    private static boolean isRelatedToClassLoader(Object obj, ClassLoader targetCL) {
        if (obj == targetCL) return true;
        if (obj instanceof Class && ((Class<?>) obj).getClassLoader() == targetCL) return true;
        return false;
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<?>> findAllLoadedLingFrameClasses() {
        List<Class<?>> result = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        ClassLoader cur = cl;
        while (cur != null) {
            try {
                Field classesField = ClassLoader.class.getDeclaredField("classes");
                classesField.setAccessible(true);
                Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(cur);
                if (classes != null) {
                    for (Class<?> c : classes) {
                        String n = c.getName();
                        if (n.startsWith("com.lingframe") || n.startsWith("org.springframework")) {
                            result.add(c);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            cur = cur.getParent();
        }
        return result;
    }

    /**
     * GC 失败后的标准诊断入口：heap dump + 可疑线程 TCCL + 灵核 Bean 字段扫描。
     *
     * @param dumpClassName  用于生成 dump 文件名的类名（通常为测试类名）
     * @param coreContext    灵核 {@code ApplicationContext}，可为 null
     */
    public static void diagnoseAfterGcFailure(String dumpClassName, Object coreContext) {
        dumpHeap(dumpClassName, true);
        diagnoseSuspectThreadsHoldingLingClassLoader();
        diagnoseSuspectBeansHoldingLingClassLoader(coreContext);
    }

    /**
     * 扫描活动线程的 contextClassLoader，找出仍指向 LingClassLoader 的线程。
     * 仅诊断输出，不修改线程状态。
     */
    public static void diagnoseSuspectThreadsHoldingLingClassLoader() {
        try {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            Thread[] threads = new Thread[root.activeCount() * 2 + 50];
            root.enumerate(threads, true);
            int suspect = 0;
            for (Thread t : threads) {
                if (t == null) {
                    continue;
                }
                ClassLoader tccl = t.getContextClassLoader();
                if (tccl == null) {
                    continue;
                }
                String tcclName = tccl.getClass().getName();
                if (tcclName.contains("LingClassLoader")) {
                    writeLog(String.format(
                            "[DIAG-Thread] suspect thread: name='%s', state=%s, daemon=%s, alive=%s, contextCL=%s@%s",
                            t.getName(), t.getState(), t.isDaemon(), t.isAlive(),
                            tcclName, Integer.toHexString(System.identityHashCode(tccl))));
                    suspect++;
                }
            }
            writeLog("[DIAG-Thread] total suspect threads holding LingClassLoader: " + suspect);
        } catch (Exception e) {
            writeLog("[DIAG-Thread] scan failed: " + e.getMessage());
        } finally {
            flushLogs();
        }
    }

    /**
     * 扫描灵核 ApplicationContext 单例 Bean 的非静态字段（深度 ≤2），
     * 找出仍持有 LingClassLoader 或由其加载的 Class 的 Bean。
     * 跳过 JDK / Spring / Servlet 标准包，仅扫业务/灵核侧类型。
     *
     * @param coreContext 灵核 ApplicationContext，非 ApplicationContext 或 null 则跳过
     */
    public static void diagnoseSuspectBeansHoldingLingClassLoader(Object coreContext) {
        if (coreContext == null) {
            return;
        }
        try {
            Method getBeanDefinitionNames = coreContext.getClass().getMethod("getBeanDefinitionNames");
            Method getBean = coreContext.getClass().getMethod("getBean", String.class);
            String[] names = (String[]) getBeanDefinitionNames.invoke(coreContext);
            if (names == null) {
                return;
            }
            int suspect = 0;
            for (String name : names) {
                Object bean;
                try {
                    bean = getBean.invoke(coreContext, name);
                } catch (Exception ignored) {
                    continue;
                }
                if (bean == null) {
                    continue;
                }
                String beanTypeName = bean.getClass().getName();
                if (beanTypeName.startsWith("java.")
                        || beanTypeName.startsWith("org.springframework.")
                        || beanTypeName.startsWith("jakarta.")
                        || beanTypeName.startsWith("javax.")) {
                    continue;
                }
                if (scanFieldsForLingClassLoader(bean, bean.getClass(), 0, 2, name)) {
                    suspect++;
                }
            }
            writeLog("[DIAG-Bean] total suspect beans holding LingClassLoader: " + suspect);
        } catch (Exception e) {
            writeLog("[DIAG-Bean] scan failed: " + e.getMessage());
        } finally {
            flushLogs();
        }
    }

    private static boolean scanFieldsForLingClassLoader(Object root, Class<?> type, int depth, int maxDepth,
                                                        String path) {
        if (root == null || depth > maxDepth) {
            return false;
        }
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(root);
                if (value == null) {
                    continue;
                }
                ClassLoader vCl = value.getClass().getClassLoader();
                if (vCl != null && vCl.getClass().getName().contains("LingClassLoader")) {
                    writeLog(String.format(
                            "[DIAG-Bean] suspect field: %s.%s -> value type=%s loaded by LingClassLoader@%s",
                            path, f.getName(), value.getClass().getName(),
                            Integer.toHexString(System.identityHashCode(vCl))));
                    return true;
                }
                if (value instanceof Class<?>) {
                    ClassLoader cCl = ((Class<?>) value).getClassLoader();
                    if (cCl != null && cCl.getClass().getName().contains("LingClassLoader")) {
                        writeLog(String.format(
                                "[DIAG-Bean] suspect Class field: %s.%s -> %s loaded by LingClassLoader@%s",
                                path, f.getName(), ((Class<?>) value).getName(),
                                Integer.toHexString(System.identityHashCode(cCl))));
                        return true;
                    }
                }
                String valueTypeName = value.getClass().getName();
                if (depth < maxDepth
                        && !valueTypeName.startsWith("java.")
                        && !valueTypeName.startsWith("org.springframework.")) {
                    if (scanFieldsForLingClassLoader(value, value.getClass(), depth + 1, maxDepth,
                            path + "." + f.getName())) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 跳过不可达字段
            }
        }
        return false;
    }

    public static void dumpHeap(String className) {
        dumpHeap(className, false);
    }

    /**
     * dump heap，可选择只 dump 存活对象。
     * liveOnly=true 时会先触发一次 Full GC 标记存活对象，只 dump 可达对象。
     * 这对于分析"对象被谁持有"至关重要——live=false 的 dump 包含不可达对象，
     * 会导致 jhat 的可达性分析产生"无 root path"的假象。
     */
    public static void dumpHeap(String className, boolean liveOnly) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String safeName = className.replace('.', '_').replace('$', '_');
            String heapDumpPath = System.getProperty("java.io.tmpdir") + "/ling-leak-" + safeName + "-" + timestamp + ".hprof";
            System.err.println("[DUMP] 正在生成 heap dump: " + heapDumpPath + " (liveOnly=" + liveOnly + ")");
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName on = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
            Object[] params = new Object[] { heapDumpPath, liveOnly };
            String[] signature = new String[] { "java.lang.String", "boolean" };
            server.invoke(on, "dumpHeap", params, signature);
            System.err.println("[DUMP] heap dump 已生成: " + heapDumpPath);
        } catch (Exception e) {
            System.err.println("[DUMP] 生成 heap dump 失败: " + e.getMessage());
        }
    }

    /**
     * 打印 GC 诊断信息：JVM 参数 + System.gc() 是否真正生效。
     * 用于排查 System.gc() 被禁用或 WeakReference 未被清理的根因。
     */
    public static void printGcDiagnostics() {
        try {
            System.err.println("[GC-DIAG] ==================== GC 诊断开始 ====================");
            // 1. 打印 JVM 输入参数，检查是否有 -XX:+DisableExplicitGC
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            System.err.println("[GC-DIAG] JVM 输入参数:");
            for (String arg : runtime.getInputArguments()) {
                System.err.println("[GC-DIAG]   " + arg);
            }
            boolean disableExplicitGc = runtime.getInputArguments().stream()
                    .anyMatch(a -> a.contains("DisableExplicitGC"));
            System.err.println("[GC-DIAG] DisableExplicitGC 存在: " + disableExplicitGc);

            // 2. 验证 System.gc() 是否真正回收 WeakReference
            Object dummy = new Object();
            WeakReference<Object> dummyRef = new WeakReference<>(dummy);
            // 擦除强引用
            dummy = null;
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            System.gc();
            boolean dummyCollected = (dummyRef.get() == null);
            System.err.println("[GC-DIAG] 简单 WeakReference 测试: System.gc() 后 referent 为 null? " + dummyCollected);
            if (!dummyCollected) {
                System.err.println("[GC-DIAG] ⚠️ System.gc() 未能回收一个简单的 WeakReference!");
                System.err.println("[GC-DIAG] ⚠️ 这说明 System.gc() 在当前 JVM 中可能被禁用或无效。");
            }

            // 3. 打印当前堆内存使用情况
            java.lang.management.MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            System.err.println("[GC-DIAG] 堆内存使用: " + memory.getHeapMemoryUsage());
            System.err.println("[GC-DIAG] GC 次数: " + ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(b -> b.getName() + "=" + b.getCollectionCount() + "/" + b.getCollectionTime() + "ms")
                    .reduce((a, b) -> a + ", " + b).orElse("none"));

            System.err.println("[GC-DIAG] ==================== GC 诊断结束 ====================");
        } catch (Exception e) {
            System.err.println("[GC-DIAG] 诊断失败: " + e.getMessage());
        }
    }
}
