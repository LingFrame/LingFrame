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

        boolean effectiveStrict = strictMode && !isTrustedLing(lingId);
        if (strictMode && !effectiveStrict) {
            log.info("[{}] 可信灵元，使用非严格模式", lingId);
        }

        try {
            AsmDangerousApiScanner.ScanResult result = AsmDangerousApiScanner.scan(source);

            result.logWarnings();

            if (effectiveStrict && result.hasWarnings()) {
                throw new LingSecurityException(lingId, "Ling contains potentially dangerous APIs");
            }

            result.throwIfCritical();

            log.info("[{}] Security scan passed", lingId);

        } catch (LingSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] Security scan failed", lingId, e);
            throw new LingSecurityException(lingId, "Failed to scan ling: " + e.getMessage(), e);
        }
    }

    private boolean isTrustedLing(String lingId) {
        return lingId != null && lingId.endsWith("-agent");
    }
}