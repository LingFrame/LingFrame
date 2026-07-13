package com.lingframe.example.saas.oauth;

import cn.hutool.core.util.RandomUtil;
import com.lingframe.example.saas.api.OAuthAbility;
import com.lingframe.example.saas.api.dto.OAuthRenderResult;
import com.lingframe.example.saas.api.dto.OAuthCallbackResult;
import org.springframework.stereotype.Component;

/**
 * SaaS 商城三方社交登录灵元能力实现。
 * <p>
 * 通过 {@code @Component} 注册到灵元 Spring 子容器，灵核侧以 {@code @LingReference OAuthAbility} 注入跨类加载器代理。
 */
@Component
public class OAuthAbilityImpl implements OAuthAbility {

    @Override
    public OAuthRenderResult render(String tenantId, String platform) {
        if ("tenant_block".equals(tenantId)) {
            throw new IllegalArgumentException("租户 " + tenantId + " 社交登录服务已被管理员限制访问");
        }
        String mockCode = RandomUtil.randomString(10);
        String mockRedirectUrl = "/api/saas/auth/social/callback/" + platform + "?code=" + mockCode + "&tenantId=" + tenantId;
        
        OAuthRenderResult result = new OAuthRenderResult();
        result.setRedirectUrl(mockRedirectUrl);
        result.setInfo("Mock redirect page for platform: " + platform + " (tenant: " + tenantId + ")");
        return result;
    }

    @Override
    public OAuthCallbackResult callback(String tenantId, String platform, String code) {
        if ("tenant_block".equals(tenantId)) {
            throw new IllegalArgumentException("租户 " + tenantId + " 社交登录服务已被管理员限制访问");
        }
        
        OAuthCallbackResult result = new OAuthCallbackResult();
        result.setOpenId("mock_openid_" + platform + "_" + RandomUtil.randomNumbers(6));
        result.setNickname("OauthMock_" + platform + "_" + RandomUtil.randomNumbers(3) + "_" + tenantId);
        result.setAvatar("https://img.lingmall.com/mock_avatar.png");
        result.setPlatform(platform);
        result.setTenantId(tenantId);
        return result;
    }
}
