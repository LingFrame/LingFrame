package com.lingframe.starter.structure;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 双栈核心过滤器一致性契约测试。
 * <p>
 * 由于 Spring Boot 2 (javax.servlet) 与 Spring Boot 3 (jakarta.servlet) 的类型系统差异，
 * 框架在各自模块中维护了对等实现类。为防止"修一边漏一边"的无意识双栈漂移，
 * 本契约测试在标准化（消除 package/import 与 servlet 命名空间差异）后比对源码一致性。
 * <p>
 * 一旦两套实现的业务逻辑发生分叉，本测试将立即 Fail-Fast 阻断构建。
 */
class DualStackConsistencyContractTest {

    private static final Path RUNTIME_ROOT = resolveRuntimeRoot();

    private static Path resolveRuntimeRoot() {
        Path current = Paths.get("").toAbsolutePath();
        Path candidate = current;
        while (candidate != null) {
            if (Files.exists(candidate.resolve("lingframe-spring-boot2-starter"))) {
                return candidate;
            }
            if (Files.exists(candidate.resolve("lingframe-runtime/lingframe-spring-boot2-starter"))) {
                return candidate.resolve("lingframe-runtime");
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot resolve lingframe-runtime root directory from current path: " + current);
    }

    @Test
    @DisplayName("契约比对：LingWebGovernanceFilter 在 Boot 2 与 Boot 3 下必须保持完全对齐")
    void testLingWebGovernanceFilterConsistency() throws IOException {
        Path sb2Path = RUNTIME_ROOT.resolve(
                "lingframe-spring-boot2-starter/src/main/java/com/lingframe/starter/filter/LingWebGovernanceFilter.java");
        Path sb3Path = RUNTIME_ROOT.resolve(
                "lingframe-spring-boot3-starter/src/main/java/com/lingframe/starter/filter/LingWebGovernanceFilter.java");

        Assertions.assertTrue(Files.exists(sb2Path), "SB2 filter must exist: " + sb2Path);
        Assertions.assertTrue(Files.exists(sb3Path), "SB3 filter must exist: " + sb3Path);

        String sb2Source = new String(Files.readAllBytes(sb2Path), StandardCharsets.UTF_8);
        String sb3Source = new String(Files.readAllBytes(sb3Path), StandardCharsets.UTF_8);

        String normalizedSb2 = normalizeSource(sb2Source, false);
        String normalizedSb3 = normalizeSource(sb3Source, false);

        Assertions.assertEquals(normalizedSb3, normalizedSb2,
                "LingWebGovernanceFilter has drifted between Boot 2 and Boot 3 implementations!");
    }

    @Test
    @DisplayName("契约比对：RepeatableReadFilter 在 Boot 2 与 Boot 3 下必须保持完全对齐")
    void testRepeatableReadFilterConsistency() throws IOException {
        Path sb2Path = RUNTIME_ROOT.resolve(
                "lingframe-spring-boot2-starter/src/main/java/com/lingframe/starter/web/JavaxRepeatableReadFilter.java");
        Path sb3Path = RUNTIME_ROOT.resolve(
                "lingframe-spring-boot3-starter/src/main/java/com/lingframe/starter/web/JakartaRepeatableReadFilter.java");

        Assertions.assertTrue(Files.exists(sb2Path), "SB2 repeatable filter must exist: " + sb2Path);
        Assertions.assertTrue(Files.exists(sb3Path), "SB3 repeatable filter must exist: " + sb3Path);

        String sb2Source = new String(Files.readAllBytes(sb2Path), StandardCharsets.UTF_8);
        String sb3Source = new String(Files.readAllBytes(sb3Path), StandardCharsets.UTF_8);

        String normalizedSb2 = normalizeSource(sb2Source, true);
        String normalizedSb3 = normalizeSource(sb3Source, true);

        Assertions.assertEquals(normalizedSb3, normalizedSb2,
                "RepeatableReadFilter has drifted between Boot 2 (Javax) and Boot 3 (Jakarta) implementations!");
    }

    @Test
    @DisplayName("契约比对：RepeatableReadFilterFactory 在 Boot 2 与 Boot 3 下必须保持完全对齐")
    void testRepeatableReadFilterFactoryConsistency() throws IOException {
        Path sb2Path = RUNTIME_ROOT.resolve(
                "lingframe-spring-boot2-starter/src/main/java/com/lingframe/starter/web/JavaxRepeatableReadFilterFactory.java");
        Path sb3Path = RUNTIME_ROOT.resolve(
                "lingframe-spring-boot3-starter/src/main/java/com/lingframe/starter/web/JakartaRepeatableReadFilterFactory.java");

        Assertions.assertTrue(Files.exists(sb2Path), "SB2 factory must exist: " + sb2Path);
        Assertions.assertTrue(Files.exists(sb3Path), "SB3 factory must exist: " + sb3Path);

        String sb2Source = new String(Files.readAllBytes(sb2Path), StandardCharsets.UTF_8);
        String sb3Source = new String(Files.readAllBytes(sb3Path), StandardCharsets.UTF_8);

        String normalizedSb2 = normalizeSource(sb2Source, true);
        String normalizedSb3 = normalizeSource(sb3Source, true);

        Assertions.assertEquals(normalizedSb3, normalizedSb2,
                "RepeatableReadFilterFactory has drifted between Boot 2 and Boot 3 implementations!");
    }

    private String normalizeSource(String source, boolean isRepeatableRead) {
        String normalized = source;
        if (isRepeatableRead) {
            normalized = normalized.replace("JavaxRepeatableReadFilter", "PlaceholderRepeatableReadFilter")
                    .replace("JakartaRepeatableReadFilter", "PlaceholderRepeatableReadFilter");
        }

        normalized = normalized.replace("javax.servlet", "placeholder.servlet")
                .replace("jakarta.servlet", "placeholder.servlet")
                .replace("Spring Boot 2.x", "Spring Boot X.x")
                .replace("Spring Boot 3.x", "Spring Boot X.x")
                .replace("面向 Spring Boot 2.x", "面向 Spring Boot X.x")
                .replace("面向 Spring Boot 3.x", "面向 Spring Boot X.x")
                .replace("SB2", "SBX")
                .replace("SB3", "SBX")
                .replace("lingframe-spring-boot2-starter", "lingframe-spring-bootX-starter")
                .replace("lingframe-spring-boot3-starter", "lingframe-spring-bootX-starter");

        StringBuilder sb = new StringBuilder();
        for (String line : normalized.split("\r?\n")) {
            String trimmed = line.trim();
            // 忽略 package、import 语句及空行
            if (trimmed.isEmpty() || trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                continue;
            }
            sb.append(trimmed).append("\n");
        }
        return sb.toString().trim();
    }
}
