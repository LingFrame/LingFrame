package com.lingframe.core.security;

import com.lingframe.core.exception.LingSecurityException;
import com.lingframe.core.spi.LingSecurityVerifier;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * API 覆盖检测器。
 * <p>
 * 防止灵元在自身包内声明 com.lingframe.api.* 类，导致契约层被覆盖。
 */
@Slf4j
public class ApiOverrideVerifier implements LingSecurityVerifier {

    private static final String API_CLASS_PATH = "com/lingframe/api/";

    @Override
    public void verify(String lingId, File source) {
        if (source == null || !source.exists()) {
            return;
        }

        try {
            boolean hasOverride = source.isDirectory()
                    ? hasApiClassesInDir(source.toPath())
                    : hasApiClassesInJar(source);

            if (hasOverride) {
                throw new LingSecurityException(lingId,
                        "Ling contains classes under com.lingframe.api.* (API override is not allowed)");
            }
        } catch (LingSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] API override scan failed", lingId, e);
            throw new LingSecurityException(lingId, "Failed to scan API override: " + e.getMessage(), e);
        }
    }

    private boolean hasApiClassesInJar(File jarFile) throws IOException {
        if (!jarFile.getName().endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.stream()
                    .map(JarEntry::getName)
                    .anyMatch(name -> name.startsWith(API_CLASS_PATH) && name.endsWith(".class"));
        }
    }

    private boolean hasApiClassesInDir(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(this::normalizePath)
                    .anyMatch(path -> path.contains("/" + API_CLASS_PATH));
        }
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
