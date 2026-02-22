package com.lingframe.core.security;

import com.lingframe.core.exception.LingSecurityException;
import com.lingframe.core.spi.LingSecurityVerifier;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 危险 API 安全验证器
 */
@Slf4j
public class DangerousApiVerifier implements LingSecurityVerifier {

    private final boolean strictMode;

    public DangerousApiVerifier() {
        this(true); // 默认严格模式
    }

    public DangerousApiVerifier(boolean strictMode) {
        this.strictMode = strictMode;
    }

    @Override
    public void verify(String lingId, File source) {
        log.info("[{}] Scanning for dangerous API calls...", lingId);

        try {
            AsmDangerousApiScanner.ScanResult result = AsmDangerousApiScanner.scan(source);

            // 记录警告
            result.logWarnings();

            // 严格模式：有警告也失败
            if (strictMode && result.hasWarnings()) {
                throw new LingSecurityException(lingId, "Ling contains potentially dangerous APIs");
            }

            // 总是拒绝关键违规
            result.throwIfCritical();

            log.info("[{}] Security scan passed", lingId);

        } catch (LingSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] Security scan failed", lingId, e);
            throw new LingSecurityException(lingId, "Failed to scan ling: " + e.getMessage(), e);
        }
    }
}