package com.lingframe.core.deploy;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.ling.LingLifecycleEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@DisplayName("DefaultLingDeployService 测试")
class DefaultLingDeployServiceTest {

    @Test
    @DisplayName("应支持通过 http uri 下载并部署灵元包")
    void shouldDeployFromHttpUri() throws Exception {
        File jarFile = File.createTempFile("ling-deploy-test", ".jar");
        jarFile.deleteOnExit();
        writeMinimalLingJar(jarFile);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/test-ling.jar", exchange -> {
            byte[] body = Files.readAllBytes(jarFile.toPath());
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class);
        AtomicReference<File> deployedFile = new AtomicReference<>();
        AtomicReference<LingDefinition> deployedDefinition = new AtomicReference<>();
        doAnswer(invocation -> {
            deployedDefinition.set(invocation.getArgument(0));
            deployedFile.set(invocation.getArgument(1));
            return null;
        }).when(lifecycleEngine).deploy(any(LingDefinition.class), any(File.class), anyBoolean(), any());

        try {
            DefaultLingDeployService deployService = new DefaultLingDeployService(lifecycleEngine);
            String uri = "http://localhost:" + server.getAddress().getPort() + "/test-ling.jar";
            deployService.deploy(uri, true);

            assertTrue(deployedFile.get() != null && deployedFile.get().exists());
            assertEquals("test-ling", deployedDefinition.get().getId());
            assertEquals("1.0.0", deployedDefinition.get().getVersion());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("不支持的 URI scheme 应明确拒绝")
    void shouldRejectUnsupportedUriScheme() throws Exception {
        DefaultLingDeployService deployService = new DefaultLingDeployService(mock(LingLifecycleEngine.class));

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> deployService.deploy("oss://bucket/demo.jar", true));
        assertEquals("URI scheme not supported yet: oss", exception.getMessage());
    }

    private void writeMinimalLingJar(File jarFile) throws Exception {
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarFile.toPath()))) {
            jarOutputStream.putNextEntry(new JarEntry("ling.yml"));
            jarOutputStream.write((
                    "id: test-ling\n" +
                    "version: 1.0.0\n" +
                    "mainClass: demo.Main\n").getBytes(StandardCharsets.UTF_8));
            jarOutputStream.closeEntry();
        }
    }
}
