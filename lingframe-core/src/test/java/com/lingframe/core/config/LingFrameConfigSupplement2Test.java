package com.lingframe.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LingFrameConfig} 的第二轮补充测试。
 * <p>
 * 已有 {@link LingFrameConfigSupplementTest} 覆盖静态状态和 Builder 默认值，
 * 此处重点覆盖 {@code jdkMajorVersion()} 的版本字符串解析三路径
 * 以及 {@code checkJdkCompatibility()} 的 JDK 16+ 分支。
 * <p>
 * 通过反射调用 private static 方法 + 临时修改系统属性实现分支覆盖。
 */
@DisplayName("LingFrameConfig 补充测试 II（JDK 版本检测分支）")
class LingFrameConfigSupplement2Test {

    @AfterEach
    void clearGlobalState() {
        LingFrameConfig.clear();
    }

    /**
     * 通过反射调用 private static jdkMajorVersion()，临时设置系统属性。
     */
    private int invokeJdkMajorVersion(String versionProperty) throws Exception {
        String original = System.getProperty("java.specification.version");
        try {
            System.setProperty("java.specification.version", versionProperty);
            Method m = LingFrameConfig.class.getDeclaredMethod("jdkMajorVersion");
            m.setAccessible(true);
            return (int) m.invoke(null);
        } finally {
            // 恢复原始值
            if (original != null) {
                System.setProperty("java.specification.version", original);
            } else {
                System.clearProperty("java.specification.version");
            }
        }
    }

    @Test
    @DisplayName("jdkMajorVersion 应正确解析 1.x 格式（JDK 8 及更早）")
    void shouldParseLegacyVersionFormat() throws Exception {
        // "1.8" → 8
        assertEquals(8, invokeJdkMajorVersion("1.8"));
    }

    @Test
    @DisplayName("jdkMajorVersion 应正确解析无点格式（如 11）")
    void shouldParseSimpleVersionFormat() throws Exception {
        // "11" → 11
        assertEquals(11, invokeJdkMajorVersion("11"));
    }

    @Test
    @DisplayName("jdkMajorVersion 应正确解析带点格式（如 17.0）")
    void shouldParseDottedVersionFormat() throws Exception {
        // "17.0" → 17
        assertEquals(17, invokeJdkMajorVersion("17.0"));
    }

    @Test
    @DisplayName("jdkMajorVersion 应正确解析更高版本（如 21）")
    void shouldParseHighVersionFormat() throws Exception {
        assertEquals(21, invokeJdkMajorVersion("21"));
    }

    @Test
    @DisplayName("系统属性缺失时应默认返回 1.8 对应的 8")
    void shouldDefaultWhenPropertyMissing() throws Exception {
        String original = System.getProperty("java.specification.version");
        try {
            System.clearProperty("java.specification.version");
            Method m = LingFrameConfig.class.getDeclaredMethod("jdkMajorVersion");
            m.setAccessible(true);
            int version = (int) m.invoke(null);
            // 默认值 "1.8" → 8
            assertEquals(8, version);
        } finally {
            if (original != null) {
                System.setProperty("java.specification.version", original);
            }
        }
    }

    @Test
    @DisplayName("init 在 JDK 16+ 模拟下应执行 --add-opens 检测且不抛异常")
    void shouldRunJdk16CompatibilityCheckWithoutError() throws Exception {
        String original = System.getProperty("java.specification.version");
        try {
            // 模拟 JDK 17 环境
            System.setProperty("java.specification.version", "17");
            LingFrameConfig config = LingFrameConfig.builder().lingHome("jdk17-test").build();
            // init 内部调用 checkJdkCompatibility → jdkMajorVersion 返回 17 → 进入 JDK 16+ 分支
            // 实际在 JDK 8 上运行，setAccessible 不会抛 InaccessibleObjectException，missing 为空
            assertDoesNotThrow(() -> LingFrameConfig.init(config));
            assertTrue(LingFrameConfig.isInitialized());
            assertSame(config, LingFrameConfig.current());
        } finally {
            LingFrameConfig.clear();
            if (original != null) {
                System.setProperty("java.specification.version", original);
            } else {
                System.clearProperty("java.specification.version");
            }
        }
    }

    @Test
    @DisplayName("init 在 JDK 8 下不应触发 --add-opens 检测（jdkVersion < 16 跳过）")
    void shouldSkipJdk16CheckOnJdk8() throws Exception {
        // 当前运行环境为 JDK 8，jdkMajorVersion 返回 8，不进入 if (jdkVersion >= 16) 分支
        LingFrameConfig config = LingFrameConfig.builder().lingHome("jdk8-test").build();
        assertDoesNotThrow(() -> LingFrameConfig.init(config));
        assertTrue(LingFrameConfig.isInitialized());
    }
}
