package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.LingPackageDTO;
import com.lingframe.dashboard.service.DashboardService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 灵元磁盘包管理控制器。
 * 提供磁盘目录包扫描、手动部署（从已有包启动）能力。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/packages")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class PackageController {

    private final DashboardService dashboardService;

    /**
     * 灵元 ID 合法字符集：字母、数字、下划线、连字符，长度 1~128。
     * 防止路径穿越字符（{@code / .. \}）传入磁盘解析逻辑导致越权加载。
     */
    private static final Pattern LING_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    /**
     * 版本号合法字符集：字母、数字、点、连字符，长度 1~64。
     * 版本号常用于拼装磁盘文件名，禁止路径穿越字符。
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-]{1,64}$");

    /**
     * 扫描磁盘包列表
     */
    @GetMapping
    public ApiResponse<List<LingPackageDTO>> listPackages() {
        try {
            return ApiResponse.ok(dashboardService.scanPackages());
        } catch (Exception e) {
            log.error("Failed to scan packages", e);
            return ApiResponse.error("扫描磁盘包失败: " + e.getMessage());
        }
    }

    /**
     * 将磁盘上已存在的物理包重新部署冷启动
     */
    @PostMapping("/deploy")
    public ApiResponse<LingInfoDTO> deployPackage(@RequestBody DeployRequest request) {
        // 输入校验：lingId / version 是磁盘文件名拼接的关键入参，
        // 必须在进入 service 层前阻断路径穿越与空值
        if (request == null) {
            return ApiResponse.error("部署失败: 请求体为空");
        }
        String lingId = request.getLingId();
        String version = request.getVersion();
        if (lingId == null || lingId.isEmpty()) {
            return ApiResponse.error("部署失败: lingId 不能为空");
        }
        if (version == null || version.isEmpty()) {
            return ApiResponse.error("部署失败: version 不能为空");
        }
        if (!LING_ID_PATTERN.matcher(lingId).matches()) {
            return ApiResponse.error("部署失败: lingId 格式非法，仅允许字母、数字、下划线、连字符");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            return ApiResponse.error("部署失败: version 格式非法，仅允许字母、数字、点、连字符");
        }
        try {
            LingInfoDTO info = dashboardService.deployPackage(lingId, version);
            return ApiResponse.ok("部署成功", info);
        } catch (Exception e) {
            log.error("Failed to deploy package: {}:{}", lingId, version, e);
            return ApiResponse.error("部署失败: " + e.getMessage());
        }
    }

    @Data
    public static class DeployRequest {
        private String lingId;
        private String version;
    }
}
