package com.lingframe.example.saas.service;

import com.lingframe.example.saas.api.OAuthAbility;
import com.lingframe.example.saas.api.dto.OAuthCallbackResult;
import com.lingframe.example.saas.api.dto.OAuthRenderResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 灵核兜底社交登录能力实现。
 * <p>
 * 与灵元 {@code OAuthAbilityImpl} 共同实现同一契约 {@link OAuthAbility},
 * 构成多 provider 契约示例(CORE 灵核 + LING 灵元),供 Dashboard 契约路由看板展示。
 * <p>
 * 灵核实现走灵核底座类加载器,被 {@code LingCoreServiceRegistrarProcessor} 扫描注册为
 * CORE provider(defaultWeight=100);灵元实现注册为 LING provider(defaultWeight=0)。
 * 调用 {@code @LingReference OAuthAbility} 时由 {@code ContractProviderRoutingFilter}
 * 按 provider 权重路由到具体提供方。
 * <p>
 * 输出数据带 "CORE" 标识,便于在 Dashboard 流量统计中区分流量来源。
 */
@Service
@Slf4j
public class OAuthAbilityCoreImpl implements OAuthAbility {

    @Override
    public OAuthRenderResult render(String tenantId, String platform) {
        if ("tenant_block".equals(tenantId)) {
            throw new IllegalArgumentException("租户 " + tenantId + " 社交登录服务已被管理员限制访问");
        }
        log.info("Core OAuth render. Tenant: {}, platform: {}", tenantId, platform);
        // 灵核兜底:返回简化重定向地址,带 CORE 标识
        String redirectUrl = "/api/saas/auth/social/callback/" + platform
                + "?code=CORE_FALLBACK&tenantId=" + tenantId;
        OAuthRenderResult result = new OAuthRenderResult();
        result.setRedirectUrl(redirectUrl);
        result.setInfo("CORE fallback render for platform: " + platform + " (tenant: " + tenantId + ")");
        return result;
    }

    @Override
    public OAuthCallbackResult callback(String tenantId, String platform, String code) {
        if ("tenant_block".equals(tenantId)) {
            throw new IllegalArgumentException("租户 " + tenantId + " 社交登录服务已被管理员限制访问");
        }
        log.info("Core OAuth callback. Tenant: {}, platform: {}, code: {}", tenantId, platform, code);
        // 灵核兜底:返回固定 mock 数据,带 CORE 标识
        OAuthCallbackResult result = new OAuthCallbackResult();
        result.setOpenId("core_openid_" + platform + "_" + tenantId);
        result.setNickname("CoreMock_" + platform + "_" + tenantId);
        result.setAvatar("https://img.lingmall.com/core_avatar.png");
        result.setPlatform(platform);
        result.setTenantId(tenantId);
        return result;
    }
}
