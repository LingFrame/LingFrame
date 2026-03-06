package com.lingframe.core.deploy;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.ling.LingLifecycleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.net.URI;
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
        } else {
            // TODO: 未来可在此扩展 HTTP/S3/OSS 下载机制
            throw new UnsupportedOperationException("URI scheme not supported yet: " + uri.getScheme());
        }

        deploy(file, isDefault);
    }

    @Override
    public void deploy(File file, boolean isDefault) throws Exception {
        LingDefinition def = LingManifestLoader.parseDefinition(file);
        // Default handling varies.
        lifecycleEngine.deploy(def, file, true, Collections.emptyMap());
    }
}
