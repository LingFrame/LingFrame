package com.lingframe.example.saas.controller;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.service.UserService;
import com.lingframe.example.saas.api.OAuthAbility;
import com.lingframe.example.saas.api.dto.OAuthRenderResult;
import com.lingframe.example.saas.api.dto.OAuthCallbackResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "SaaS-1. 用户社交登录代理端点 (SaaS 灵核)", description = "代理委派社交登录灵元执行校验与解析")
@RestController
@RequestMapping("/api/saas/auth")
@RequiredArgsConstructor
public class SaasAuthController {

    private final UserService userService;

    // 显性契约注入：使用 @LingReference 由灵珑底层自动织入动态路由治理代理，平滑跨越类加载器边界
    @LingReference
    private OAuthAbility oAuthAbility;

    @Operation(summary = "SaaS 社交登录渲染", description = "委托 OAuth 登录灵元渲染三方登录重定向页")
    @GetMapping("/social/render/{platform}")
    public ResponseResult<OAuthRenderResult> socialRender(
            @RequestParam String tenantId,
            @PathVariable String platform) {
        OAuthRenderResult result = oAuthAbility.render(tenantId, platform);
        return ResponseResult.success(result);
    }

    @Operation(summary = "SaaS 社交登录授权回调", description = "由登录灵元完成解析，再由灵核底座进行绑定注册登录")
    @GetMapping("/social/callback/{platform}")
    public ResponseResult<Map<String, String>> socialCallback(
            @RequestParam String tenantId,
            @PathVariable String platform, 
            @RequestParam String code) {
        
        // 1. 委托灵元执行三方校验和三方数据解析，返回强类型契约对象
        OAuthCallbackResult callbackData = oAuthAbility.callback(tenantId, platform, code);
        
        // 2. 灵核底座执行本地用户账户绑定和登录（OAuthCallbackResult 为强类型契约 DTO）
        String openId = callbackData.getOpenId();
        String nickname = callbackData.getNickname();
        String avatar = callbackData.getAvatar();
        
        String token = userService.socialLogin(platform, openId, nickname, avatar);
        
        Map<String, String> result = new HashMap<>();
        result.put("platform", platform);
        result.put("openId", openId);
        result.put("token", token);
        return ResponseResult.success(result);
    }
}
