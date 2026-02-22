package com.lingframe.starter.deploy;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.ling.LingManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.net.URI;

@Slf4j
@RequiredArgsConstructor
public class DefaultLingDeployService implements LingDeployService {

    private final LingManager lingManager;

    @Override
    public void deploy(String uriString, boolean isDefault) throws Exception {
        log.info("Deploying Ling from URI: {}", uriString);
        URI uri = new URI(uriString);

        File file;
        if ("file".equalsIgnoreCase(uri.getScheme()) || uri.getScheme() == null) {
            file = new File(uri.getPath());
        } else {
            // TODO: 未来可在此扩展 HTTP/S3/OSS 下载机制
            throw new UnsupportedOperationException("URI scheme not supported yet: " + uri.getScheme());
        }

        deploy(file, isDefault);
    }

    @Override
    public void deploy(File file, boolean isDefault) throws Exception {
        LingDefinition def = LingManifestLoader.parseDefinition(file);
        // Note: LingManager install may not accept isDefault right now,
        // passing def and file is the standard. Default handling varies.
        lingManager.install(def, file);
    }
}
