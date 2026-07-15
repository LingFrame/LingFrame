package com.lingframe.core.security;

import com.lingframe.core.exception.LingSecurityException;
import com.lingframe.core.spi.LingSecurityVerifier;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 危险 API 安全验证器
 * <p>
 * 可信灵元判定基于显式白名单 {@code trustedLingIds}（构造时传入），
 * 不再使用 "-agent" 后缀判定——后缀判定可被恶意灵元绕过。
 */
@Slf4j
public class DangerousApiVerifier implements LingSecurityVerifier {

    private final boolean strictMode;
    private final Set<String> trustedLingIds;

    /**
     * @param strictMode      是否严格模式
     * @param trustedLingIds  可信灵元 ID 白名单（来自 LingFrameConfig.trustedLingIds），
     *                        白名单中的灵元即使严格模式也使用非严格模式
     */
    public DangerousApiVerifier(boolean strictMode, Collection<String> trustedLingIds) {
        this.strictMode = strictMode;
        this.trustedLingIds = trustedLingIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(trustedLingIds));
    }

    @Override
    public void verify(String lingId, File source) {
        log.info("[{}] Scanning for dangerous API calls...", lingId);

        boolean effectiveStrict = strictMode && !isTrustedLing(lingId);
        if (strictMode && !effectiveStrict) {
            log.info("[{}] Trusted ling, using non-strict mode", lingId);
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

    /**
     * 判断灵元是否在显式白名单中。
     * <p>
     * 不再使用 "-agent" 后缀判定——后缀判定可被恶意灵元绕过（如命名为 evil-agent）。
     */
    private boolean isTrustedLing(String lingId) {
        return lingId != null && trustedLingIds.contains(lingId);
    }
}