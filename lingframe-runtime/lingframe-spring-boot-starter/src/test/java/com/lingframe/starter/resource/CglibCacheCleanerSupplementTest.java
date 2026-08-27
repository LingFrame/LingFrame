package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.NoOp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link CglibCacheCleaner} 补充测试。
 * <p>
 * 该类为包级可见 final 类，测试置于同包下直接访问。
 * 重点覆盖 Spring 5.x / 6.x 分支选择、共享加载器跳过语义、
 * ClassLoader.classes 扫描容错、null ClassLoader 安全处理。
 */
@DisplayName("CglibCacheCleaner 补充测试")
class CglibCacheCleanerSupplementTest {

    /** 回归用普通父类：用于在系统 ClassLoader 中生成 CGLIB 子类 */
    static class SharedBoundaryTarget {
    }

    @Test
    @DisplayName("Spring 5.x 模式下系统 ClassLoader 应被跳过（共享增强类不受影响）")
    void shouldSkipSystemClassLoaderWithSpring5() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", systemCl));
    }

    @Test
    @DisplayName("Spring 6.x 模式下系统 ClassLoader 应被跳过（共享增强类不受影响）")
    void shouldSkipSystemClassLoaderWithSpring6() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(6);
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        assertDoesNotThrow(() -> cleaner.clear("ling-a", systemCl));
    }

    @Test
    @DisplayName("自定义 ClassLoader 下 Spring 5.x 模式应安全执行")
    void shouldSafelyClearWithCustomClassLoader() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader customCl = new ClassLoader() {
        };

        assertDoesNotThrow(() -> cleaner.clear("ling-a", customCl));
    }

    @Test
    @DisplayName("null lingId 下应安全执行不抛异常")
    void shouldHandleNullLingId() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader cl = new ClassLoader() {
        };

        assertDoesNotThrow(() -> cleaner.clear(null, cl));
    }

    @Test
    @DisplayName("多次调用 clear 应幂等不抛异常")
    void shouldBeIdempotent() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader cl = new ClassLoader() {
        };

        assertDoesNotThrow(() -> {
            cleaner.clear("ling-a", cl);
            cleaner.clear("ling-a", cl);
        });
    }

    @Test
    @DisplayName("系统 ClassLoader 应被跳过：clear 后同一父类可再次被 CGLIB 生成子类（无 LinkageError）")
    void shouldSkipSystemClassLoaderAndAllowReEnhancement() {
        CglibCacheCleaner cleaner = new CglibCacheCleaner(5);
        ClassLoader systemCl = ClassLoader.getSystemClassLoader();

        // 第一次生成：在系统 CL 中定义 CGLIB 子类（对应灵核首次创建 Spring 上下文时的增强）
        Class<?> enhanced = assertDoesNotThrow(() -> createEnhancedClass(systemCl));
        assertNotNull(enhanced);

        // 对系统 CL 执行清理：修复前会删除 CGLIB CACHE 条目并清空 ClassLoaderData，
        // 导致后续重建上下文时重新 defineClass 同名增强类 → LinkageError
        cleaner.clear("ling-a", systemCl);

        // 修复后：共享加载器被跳过，CGLIB 缓存仍有效，再次生成应复用缓存成功
        assertDoesNotThrow(() -> createEnhancedClass(systemCl));
    }

    private static Class<?> createEnhancedClass(ClassLoader classLoader) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(SharedBoundaryTarget.class);
        enhancer.setClassLoader(classLoader);
        enhancer.setCallbackType(NoOp.class);
        return enhancer.createClass();
    }
}
