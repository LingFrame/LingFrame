package com.lingframe.core.ling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BusinessInterfaceFilter} 独立单测。
 * <p>
 * 覆盖：core 默认排除前缀 / 生态环境追加 / 用户追加 / null 安全。
 */
@DisplayName("BusinessInterfaceFilter 测试")
class BusinessInterfaceFilterTest {

    /** 测试用业务接口（不在任何排除前缀内） */
    public interface TestBusinessService {
        void execute();
    }

    /** 测试用 JDK 接口（应被排除） */
    public interface TestJdkMarker extends java.io.Serializable {
    }

    @Nested
    @DisplayName("core 默认排除")
    class CoreDefaults {

        @Test
        @DisplayName("coreDefaults() 应排除 java.* 接口")
        void shouldExcludeJdkInterfaces() {
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.coreDefaults();

            assertFalse(filter.isBusinessInterface(java.io.Serializable.class));
            assertFalse(filter.isBusinessInterface(java.lang.Runnable.class));
        }

        @Test
        @DisplayName("coreDefaults() 应排除 com.lingframe.core.* 接口")
        void shouldExcludeLingFrameCoreInterfaces() {
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.coreDefaults();

            // BusinessInterfaceFilter 本身在 com.lingframe.core.ling.* 下，应被排除
            assertFalse(filter.isBusinessInterface(BusinessInterfaceFilter.class));
        }

        @Test
        @DisplayName("clearCoreDefaults 后应保留测试嵌套业务接口")
        void shouldKeepBusinessVariablesAfterClearCoreDefaults() {
            // 测试嵌套接口包名落在 com.lingframe.core.* 下，coreDefaults() 会排除它；
            // 用 clearCoreDefaults() 清空默认前缀，仅排除 JDK 基础接口，验「保留」语义。
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder()
                    .clearCoreDefaults()
                    .ecosystemExcluded(Collections.singletonList("java."))
                    .build();

            assertTrue(filter.isBusinessInterface(TestBusinessService.class));
            // JDK 接口仍应被排除（我们追加了 java. 前缀）
            assertFalse(filter.isBusinessInterface(java.io.Serializable.class));
        }

        @Test
        @DisplayName("coreDefaults() 应排除 org.slf4j.* 接口（slf4j 属 core 基础设施）")
        void shouldExcludeSlf4jInterfaces() {
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.coreDefaults();

            // slf4j 与 lombok 同属 core 基础设施，不应被视为业务接口
            assertFalse(filter.isBusinessInterface(org.slf4j.Logger.class),
                    "org.slf4j.Logger 应被 core 默认排除前缀排除");
        }
    }

    @Nested
    @DisplayName("Builder 追加排除")
    class BuilderExclusion {

        @Test
        @DisplayName("ecosystemExcluded() 追加 Spring 等生态环境前缀")
        void shouldAddEcosystemExcluded() {
            // 测试嵌套接口包名在 com.lingframe.core.* 下，core 默认会排除——清空后再追加
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder()
                    .clearCoreDefaults()
                    .ecosystemExcluded(Collections.singletonList("org.springframework."))
                    .build();

            assertTrue(filter.isBusinessInterface(TestBusinessService.class));
        }

        @Test
        @DisplayName("userExcluded() 追加用户自定义排除前缀")
        void shouldAddUserExcluded() {
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder()
                    .clearCoreDefaults()
                    .userExcluded(Collections.singletonList("com.example.internal."))
                    .build();

            // 假接口名 com.example.internal.* 应被排除——业务接口不被误排除
            assertTrue(filter.isBusinessInterface(TestBusinessService.class));
        }

        @Test
        @DisplayName("ecosystemExcluded(null) 应不崩且保留追加能力")
        void shouldHandleNullEcosystemExcluded() {
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.builder()
                    .clearCoreDefaults()
                    .ecosystemExcluded(null)
                    .userExcluded(null)
                    .build();

            assertNotNull(filter);
            // 清空 core 默认后，测试嵌套接口应被视为业务接口（不在任何排除前缀内）
            assertTrue(filter.isBusinessInterface(TestBusinessService.class));
            // JDK 接口不被排除（清空后没追加 java. 前缀）
            assertTrue(filter.isBusinessInterface(java.io.Serializable.class));
        }
    }

    @Nested
    @DisplayName("null 安全")
    class NullSafety {

        @Test
        @DisplayName("isBusinessInterface(null) 应返回 false 不崩")
        void shouldReturnFalseOnNull() {
            BusinessInterfaceFilter filter = BusinessInterfaceFilter.coreDefaults();

            assertFalse(filter.isBusinessInterface(null));
        }
    }
}
