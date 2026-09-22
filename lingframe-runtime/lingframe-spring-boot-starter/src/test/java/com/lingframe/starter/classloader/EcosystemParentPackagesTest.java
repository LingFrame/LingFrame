package com.lingframe.starter.classloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EcosystemParentPackages} 单测。
 * <p>
 * 验证适配层生态环境委派清单的「内容稳定」+「不可变」语义——
 * core 的 core 白名单拆出后，生态环境包由此类独持，清单漂移会直接导致灵元/灵核 ClassCastException。
 */
@DisplayName("EcosystemParentPackages 适配层生态委派清单测试")
class EcosystemParentPackagesTest {

    @Nested
    @DisplayName("清单内容")
    class Content {

        @Test
        @DisplayName("应包含 Spring / Jackson / Logback / Log4j2 四项生态环境包前缀")
        void shouldContainCoreEcosystemPackages() {
            Collection<String> packages = EcosystemParentPackages.ecosystemDefaults();

            assertNotNull(packages);
            assertTrue(packages.contains("org.springframework."), "Spring 委派缺失");
            assertTrue(packages.contains("com.fasterxml.jackson."), "Jackson 委派缺失");
            assertTrue(packages.contains("ch.qos.logback."), "Logback 委派缺失");
            assertTrue(packages.contains("org.apache.logging.log4j."), "Log4j2 委派缺失");
            assertEquals(4, packages.size(), "生态清单项数漂移，预期 4 项");
        }

        @Test
        @DisplayName("不应包含灵珑自身门面——slf4j/lombok/snakeyaml 属 core 白名单，不在此")
        void shouldNotContainLingFrameSelfFacets() {
            Collection<String> packages = EcosystemParentPackages.ecosystemDefaults();

            // 这些是灵珑自身依赖，留在 core 的 FORCE_PARENT_PACKAGES，不应散回适配层
            assertFalse(packages.contains("org.slf4j."), "slf4j 应留 core，不应在适配层");
            assertFalse(packages.contains("lombok."), "lombok 应留 core，不应在适配层");
            assertFalse(packages.contains("org.yaml.snakeyaml."), "snakeyaml 应留 core，不应在适配层");
        }
    }

    @Nested
    @DisplayName("不可变语义")
    class Immutability {

        @Test
        @DisplayName("ecosystemDefaults() 返回视图应不可变，防调用方误改清单")
        void shouldReturnImmutableView() {
            Collection<String> packages = EcosystemParentPackages.ecosystemDefaults();

            assertNotNull(packages);
            // Collections.unmodifiableSet 包裹 → 任何 mutate 操作应抛 UnsupportedOperationException
            assertThrows(UnsupportedOperationException.class, () -> packages.remove("org.springframework."));
            assertThrows(UnsupportedOperationException.class, () -> packages.add("com.example."));
        }

        @Test
        @DisplayName("多次调用 ecosystemDefaults() 应返回等价内容（静态清单，无状态漂移）")
        void shouldReturnStableContentAcrossCalls() {
            Collection<String> first = EcosystemParentPackages.ecosystemDefaults();
            Collection<String> second = EcosystemParentPackages.ecosystemDefaults();

            assertEquals(first, second, "静态清单多次调用应内容一致");
            assertEquals(first.size(), second.size());
        }
    }
}
