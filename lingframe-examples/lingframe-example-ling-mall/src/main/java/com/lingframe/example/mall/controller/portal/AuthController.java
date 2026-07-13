package com.lingframe.example.mall.controller.portal;

import cn.hutool.core.util.RandomUtil;
import com.lingframe.example.mall.config.AuditLog;
import com.lingframe.example.mall.dto.LoginRequest;
import com.lingframe.example.mall.dto.RegisterRequest;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "1. 用户与认证接口", description = "提供用户注册、密码登录及Mock三方社交登录")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "用户注册", description = "注册新账户，并自动生成对应的会员成长卡档案")
    @PostMapping("/register")
    public ResponseResult<Void> register(@Validated @RequestBody RegisterRequest request) {
        userService.register(request);
        return ResponseResult.success();
    }

    @Operation(summary = "用户名密码登录", description = "校验并生成包含角色及细粒度按钮权限的 JWT Token")
    @PostMapping("/login")
    @AuditLog(action = "LOGIN", resource = "auth")
    public ResponseResult<Map<String, String>> login(@Validated @RequestBody LoginRequest request) {
        String token = userService.login(request);
        Map<String, String> result = new HashMap<>();
        result.put("token", token);
        return ResponseResult.success(result);
    }

    @Operation(summary = "Mock社交登录重定向渲染", description = "模拟重定向至Gitee/GitHub授权登录页")
    @GetMapping("/social/render/{platform}")
    public ResponseResult<Map<String, String>> socialRender(@PathVariable String platform) {
        String mockCode = RandomUtil.randomString(10);
        String mockRedirectUrl = "/api/auth/social/callback/" + platform + "?code=" + mockCode;
        Map<String, String> result = new HashMap<>();
        result.put("redirectUrl", mockRedirectUrl);
        result.put("info", "请直接请求 redirectUrl 完成社交绑定登录");
        return ResponseResult.success(result);
    }

    @Operation(summary = "Mock社交登录授权回调", description = "授权回调，模拟通过Code获取三方账号绑定本地新号登录")
    @GetMapping("/social/callback/{platform}")
    @AuditLog(action = "SOCIAL_LOGIN", resource = "auth")
    public ResponseResult<Map<String, String>> socialCallback(@PathVariable String platform, @RequestParam String code) {
        String mockOpenId = "mock_openid_" + platform + "_" + RandomUtil.randomNumbers(6);
        String mockNickname = "Mock_" + platform + "_" + RandomUtil.randomNumbers(3);
        String mockAvatar = "https://img.lingmall.com/mock_avatar.png";

        String token = userService.socialLogin(platform, mockOpenId, mockNickname, mockAvatar);
        
        Map<String, String> result = new HashMap<>();
        result.put("platform", platform);
        result.put("openId", mockOpenId);
        result.put("nickname", mockNickname);
        result.put("token", token);
        return ResponseResult.success(result);
    }
}
