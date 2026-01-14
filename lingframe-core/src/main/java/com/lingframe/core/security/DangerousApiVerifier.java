package com.lingframe.core.security;

import com.lingframe.core.exception.PluginSecurityException;
import com.lingframe.core.spi.PluginSecurityVerifier;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 危险 API 安全验证器
 */
@Slf4j
public class DangerousApiVerifier implements PluginSecurityVerifier {

    private final boolean strictMode;

    public DangerousApiVerifier() {
        this(true); // 默认严格模式
    }

    public DangerousApiVerifier(boolean strictMode) {
        this.strictMode = strictMode;
    }

    @Override
    public void verify(String pluginId, File source) {
        log.info("[{}] Scanning for dangerous API calls...", pluginId);

        try {
            AsmDangerousApiScanner.ScanResult result = AsmDangerousApiScanner.scan(source);

            // 记录警告
            result.logWarnings();

            // 严格模式：有警告也失败
            if (strictMode && result.hasWarnings()) {
                throw new PluginSecurityException(pluginId, "Plugin contains potentially dangerous APIs");
            }

            // 总是拒绝关键违规
            result.throwIfCritical();

            log.info("[{}] Security scan passed", pluginId);

        } catch (PluginSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] Security scan failed", pluginId, e);
            throw new PluginSecurityException(pluginId, "Failed to scan plugin: " + e.getMessage(), e);
        }
    }
}