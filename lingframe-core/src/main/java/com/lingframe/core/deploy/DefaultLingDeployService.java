package com.lingframe.core.deploy;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.ling.LingLifecycleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;

@Slf4j
@RequiredArgsConstructor
public class DefaultLingDeployService implements LingDeployService {

    private final LingLifecycleEngine lifecycleEngine;

    @Override
    public void deploy(String uriString, boolean isDefault) throws Exception {
        log.info("Deploying Ling from URI: {}", uriString);
        URI uri = new URI(uriString);

        File file;
        if ("file".equalsIgnoreCase(uri.getScheme()) || uri.getScheme() == null) {
            file = new File(uri.getPath());
        } else if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
            file = downloadToTempFile(uri);
        } else {
            throw new UnsupportedOperationException("URI scheme not supported yet: " + uri.getScheme());
        }

        deploy(file, isDefault);
    }

    @Override
    public void deploy(File file, boolean isDefault) throws Exception {
        LingDefinition def = LingManifestLoader.parseDefinition(file);
        // 默认部署标记的具体处理由生命周期引擎统一决定。
        lifecycleEngine.deploy(def, file, isDefault, Collections.emptyMap());
    }

    private File downloadToTempFile(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Failed to download Ling package from " + uri + ", status=" + status);
        }

        String targetName = extractFileName(uri);
        File tempFile = File.createTempFile("ling-deploy-", "-" + targetName);
        tempFile.deleteOnExit();

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }

        log.info("Downloaded Ling package from {} to {}", uri, tempFile.getAbsolutePath());
        return tempFile;
    }

    private String extractFileName(URI uri) {
        String path = uri.getPath();
        if (path == null || path.trim().isEmpty()) {
            return "package.jar";
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name == null || name.trim().isEmpty() ? "package.jar" : name;
    }
}
