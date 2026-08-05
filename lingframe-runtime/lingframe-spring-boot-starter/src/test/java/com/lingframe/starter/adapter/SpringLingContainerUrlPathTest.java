package com.lingframe.starter.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SpringLingContainer.extractPathFromUrl 单测。
 * 方法为 private static，通过反射调用。
 */
@DisplayName("SpringLingContainer URL 路径提取")
class SpringLingContainerUrlPathTest {

    private static final Method EXTRACT_METHOD;

    static {
        try {
            EXTRACT_METHOD = SpringLingContainer.class.getDeclaredMethod("extractPathFromUrl", URL.class);
            EXTRACT_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("extractPathFromUrl 方法未找到", e);
        }
    }

    private static String extractPath(URL url) {
        try {
            return (String) EXTRACT_METHOD.invoke(null, url);
        } catch (Exception e) {
            throw new AssertionError("反射调用 extractPathFromUrl 失败", e);
        }
    }

    @Nested
    @DisplayName("file 协议")
    class FileProtocol {

        @Test
        @DisplayName("Windows 绝对路径：file:/E:/Codes/app/Service.class")
        void windowsAbsolutePath() throws Exception {
            URL url = new URL("file:/E:/Codes/app/Service.class");
            assertEquals("/E:/Codes/app/Service.class", extractPath(url));
        }

        @Test
        @DisplayName("Linux 绝对路径：file:/home/user/app/Service.class")
        void linuxAbsolutePath() throws Exception {
            URL url = new URL("file:/home/user/app/Service.class");
            assertEquals("/home/user/app/Service.class", extractPath(url));
        }
    }

    @Nested
    @DisplayName("jar 协议")
    class JarProtocol {

        @Test
        @DisplayName("jar 内条目：jar:file:/E:/Codes/app.jar!/Service.class")
        void jarFileEntry() throws Exception {
            URL url = new URL("jar:file:/E:/Codes/app.jar!/Service.class");
            assertEquals("/E:/Codes/app.jar!/Service.class", extractPath(url));
        }

        @Test
        @DisplayName("jar 内条目（Linux）：jar:file:/home/user/app.jar!/Service.class")
        void jarFileEntryLinux() throws Exception {
            URL url = new URL("jar:file:/home/user/app.jar!/Service.class");
            assertEquals("/home/user/app.jar!/Service.class", extractPath(url));
        }
    }
}
