package com.lingframe.example.saas.oauth.controller;

import cn.hutool.core.util.RandomUtil;
import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.example.mall.dto.LoginRequest;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * SaaS 商城社交登录灵元 HTTP 入口。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>灵元自暴露 @RestController，由 {@code WebInterfaceManager} 注册到灵核 Spring MVC，
 *       灵核 Spring MVC 只持有 {@code LingWebEntryHandler}（灵核类），不接触灵元类，保证灵元可热卸载。</li>
 *   <li>tenantId 从请求头 {@code X-Tenant-Id} 读取，写入 {@link LingCallContext} 的 label，
 *       灵元 SaaSUserServiceImpl 内部从 label 取 tenantId 做多租户治理。
 *       请求结束由 {@code LingWebGovernanceFilter} 统一 clear，无 ThreadLocal 泄漏。</li>
 *   <li>{@code @LingReference UserService} 不指定 lingId，走路由层双 provider 权重切流：
 *       默认灵核 100/灵元 0 走灵核原生实现；
 *       Dashboard 调权重到灵核 0/灵元 100 后，走灵元 SaaS 多租户实现（覆盖点）。</li>
 * </ul>
 */
@Tag(name = "SaaS-Ling-1. 社交登录灵元入口 (灵元自暴露)", description = "灵元 HTTP 入口级切流演示：覆盖灵核 UserService.socialLogin")
@RestController
@RequestMapping("/api/saas/ling/auth")
public class SaaSAuthController {

    // 不指定 lingId：走 UserService 契约的双 provider 权重切流
    @LingReference
    private UserService userService;

    @Operation(summary = "账号密码登录", description = "灵元入口：走双 provider 切流调用 login")
    @PostMapping("/login")
    public ResponseResult<String> login(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody LoginRequest loginRequest) {
        setTenantLabel(tenantId);
        String token = userService.login(loginRequest);
        return ResponseResult.success(token);
    }

    @Operation(summary = "SaaS 社交登录", description = "灵元入口：mock 平台回调数据，走双 provider 切流调用 socialLogin")
    @PostMapping("/social/login/{platform}")
    public ResponseResult<String> socialLogin(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @PathVariable String platform,
            @RequestParam(required = false) String openId,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String avatar) {

        // 写入 tenant label：灵元 SaaSUserServiceImpl.socialLogin 内部从 LingCallContext 读取
        setTenantLabel(tenantId);

        // mock 平台回调数据（caller 未提供时生成默认值）
        String resolvedOpenId = openId != null ? openId
                : "mock_" + platform + "_" + RandomUtil.randomNumbers(6);
        String resolvedNickname = nickname != null ? nickname
                : "SaaSMock_" + platform + "_" + RandomUtil.randomNumbers(3);
        String resolvedAvatar = avatar != null ? avatar
                : "https://img.lingmall.com/saas_avatar.png";

        // 走双 provider 切流：默认灵核原生 socialLogin，Dashboard 切流后灵元 SaaS 多租户 socialLogin
        String token = userService.socialLogin(platform, resolvedOpenId, resolvedNickname, resolvedAvatar);
        return ResponseResult.success(token);
    }

    private void setTenantLabel(String tenantId) {
        if (tenantId != null) {
            Map<String, String> labels = new HashMap<>();
            labels.put("tenant", tenantId);
            LingCallContext.setLabels(labels);
        }
    }
}
