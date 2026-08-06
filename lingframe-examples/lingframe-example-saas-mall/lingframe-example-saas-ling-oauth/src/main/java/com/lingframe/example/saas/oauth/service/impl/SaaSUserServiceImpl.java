package com.lingframe.example.saas.oauth.service.impl;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.example.mall.dto.LoginRequest;
import com.lingframe.example.mall.dto.RegisterRequest;
import com.lingframe.example.mall.entity.User;
import com.lingframe.example.mall.service.UserService;
import com.lingframe.infra.mybatisplus.DelegatingIServiceSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SaaS 商城社交登录灵元——覆盖灵核 {@link UserService} 实现。
 * <p>
 * 设计要点（绞杀迁移示例之一：覆盖原接口）：
 * <ul>
 *   <li>灵元与灵核 {@code UserServiceImpl} 共同实现同一契约 {@link UserService}，
 *       由路由层按 provider 权重在灵核/灵元之间切流，Dashboard 调权重即可完成流量迁移。</li>
 *   <li>灵元不持有 DataSource，IService 桩方法由 {@link DelegatingIServiceSupport} 统一 delegate 到灵核，
 *       子类只写覆盖点，零桩代码。</li>
 *   <li>覆盖点：{@link #socialLogin} 在灵核实现之上叠加 SaaS 多租户治理——
 *       限制性租户拦截 + openId 加租户前缀做社交账号命名空间隔离。</li>
 *   <li>非覆盖点（login/register）：delegate 到灵核，保持 UserService 契约完整性。</li>
 * </ul>
 * tenantId 不进方法签名，由 HTTP 入口写入请求头 label，路由层精准命中灵元 provider。
 */
@Slf4j
@Component
public class SaaSUserServiceImpl extends DelegatingIServiceSupport<User> implements UserService {

    /**
     * 显式 pinning 到灵核：避免灵元→灵元自调用循环。
     * lingId 固定为灵核 ID，路由层不再做双 provider 切流，直接命中灵核 UserServiceImpl。
     */
    @LingReference(lingId = "lingcore-app")
    private UserService coreUserService;

    @Override
    protected IService<User> getCoreService() {
        return coreUserService;
    }

    /**
     * 覆盖灵核 socialLogin：叠加 SaaS 多租户治理。
     * <p>
     * 灵核版本：所有租户共享一张 SocialUser 表，openId 全局唯一。
     * 灵元版本：限制性租户拦截 + openId 加租户前缀隔离，避免不同租户的同名 openId 冲突。
     */
    @Override
    public String socialLogin(String platform, String openId, String nickname, String avatar) {
        String tenantId = LingCallContext.getLabels().get("tenant");
        if ("tenant_block".equals(tenantId)) {
            throw new IllegalArgumentException("租户 " + tenantId + " 社交登录服务已被管理员限制访问");
        }
        // 租户命名空间隔离：openId 加 tenantId 前缀，保证不同租户的同平台 openId 不冲突
        String tenantScopedOpenId = (tenantId == null ? "" : tenantId + ":") + openId;
        log.info("SaaS socialLogin override. tenant={}, platform={}, openId={}->{}",
                tenantId, platform, openId, tenantScopedOpenId);
        return coreUserService.socialLogin(platform, tenantScopedOpenId, nickname, avatar);
    }

    // —— 非覆盖点：delegate 到灵核，保持 UserService 契约完整 ——

    @Override
    public String login(LoginRequest loginRequest) {
        return coreUserService.login(loginRequest);
    }

    @Override
    public void register(RegisterRequest registerRequest) {
        coreUserService.register(registerRequest);
    }
}
