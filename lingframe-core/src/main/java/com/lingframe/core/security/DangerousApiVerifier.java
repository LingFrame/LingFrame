package com.lingframe.core.security;

import com.lingframe.core.exception.LingSecurityException;
import com.lingframe.core.spi.LingSecurityVerifier;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 危险 API 安全验证器
 * <p>
 * 可信灵元判定基于显式白名单 {@code trustedLingIds}（构造时传入），
 * 不再使用 "-agent" 后缀判定——后缀判定可被恶意灵元绕过。
 */
@Slf4j
public class DangerousApiVerifier implements LingSecurityVerifier {

    /** 异常 message 中携带的违规清单上限，防止超长 message 撑爆日志/MCP 响应 */
    private static final int MAX_VIOLATIONS_IN_MESSAGE = 20;

    private final boolean strictMode;
    private final Set<String> trustedLingIds;
    private final Set<String> trustedLibPrefixes;

    /**
     * @param strictMode          是否严格模式
     * @param trustedLingIds      可信灵元 ID 白名单（来自 LingFrameConfig.trustedLingIds），
     *                            白名单中的灵元即使严格模式也使用非严格模式
     * @param trustedLibPrefixes  依赖库包前缀豁免列表（点号或斜杠形式均可，内部归一化），
     *                            匹配前缀的类会被扫描器跳过，解决胖包/shade 包内依赖库
     *                            反射调用触发 WARN 的问题；null 或空表示不豁免
     */
    public DangerousApiVerifier(boolean strictMode, Collection<String> trustedLingIds,
            Collection<String> trustedLibPrefixes) {
        this.strictMode = strictMode;
        this.trustedLingIds = trustedLingIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(trustedLingIds));
        this.trustedLibPrefixes = trustedLibPrefixes == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(trustedLibPrefixes));
    }

    @Override
    public void verify(String lingId, File source) {
        log.info("[{}] Scanning for dangerous API calls... (strictMode={}, trustedLibPrefixes={})",
                lingId, strictMode, trustedLibPrefixes.size());

        boolean effectiveStrict = strictMode && !isTrustedLing(lingId);
        if (strictMode && !effectiveStrict) {
            log.info("[{}] Trusted ling, using non-strict mode", lingId);
        }

        try {
            AsmDangerousApiScanner.ScanResult result = AsmDangerousApiScanner.scan(source, trustedLibPrefixes);

            result.logWarnings();

            // CRITICAL 违规优先暴露：绝对禁止的 API 比 WARN 更严重，必须先抛，
            // 否则严格模式下同时含 CRITICAL+WARNING 时只报 WARNING，CRITICAL 被掩盖
            result.throwIfCritical();

            if (effectiveStrict && result.hasWarnings()) {
                throw new LingSecurityException(lingId, buildStrictModeMessage(lingId, result));
            }

            log.info("[{}] Security scan passed", lingId);

        } catch (LingSecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] Security scan failed", lingId, e);
            throw new LingSecurityException(lingId, "Failed to scan ling: " + e.getMessage(), e);
        }
    }

    /**
     * 构建严格模式拦截异常的 message，携带具体违规清单与修复提示。
     * <p>
     * 历史实现只返回 "Ling contains potentially dangerous APIs"，调用方（含 MCP 工具）
     * 无法定位是哪个类/哪个 API 触发拦截，导致诊断需要翻日志。改为把前 N 条违规
     * 拼进 message，让 DeployTool 能直接回传给 IDE。
     */
    private String buildStrictModeMessage(String lingId, AsmDangerousApiScanner.ScanResult result) {
        List<String> violationLines = result.getWarnings().stream()
                .map(Object::toString)
                .limit(MAX_VIOLATIONS_IN_MESSAGE)
                .collect(Collectors.toList());

        int total = result.getWarnings().size();
        StringBuilder sb = new StringBuilder("Ling contains potentially dangerous APIs (strict mode).");
        sb.append(" lingId=").append(lingId)
          .append(", totalViolations=").append(total);
        if (total > MAX_VIOLATIONS_IN_MESSAGE) {
            sb.append(" (showing first ").append(MAX_VIOLATIONS_IN_MESSAGE).append(")");
        }
        sb.append("\nViolations:");
        for (String line : violationLines) {
            sb.append("\n  ").append(line);
        }
        sb.append("\nHint: ")
          .append("add ling id to lingframe.trusted-ling-ids to bypass, ")
          .append("or configure lingframe.security.trusted-lib-prefixes to skip dependency libs, ")
          .append("or set lingframe.security.strict-mode=false in dev environment.");
        return sb.toString();
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