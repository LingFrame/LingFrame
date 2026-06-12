package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.storage.MetricsStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 历史指标查询 API
 *
 * 当 MetricsStorage Bean 不存在时（storage.enabled=false），
 * Spring 会跳过此 Controller 的创建（构造函数注入失败 → Bean 不注册），
 * 效果等同于 @ConditionalOnBean，但避免了类级别 @ConditionalOnBean 的 Bean 定义顺序问题。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/metrics")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MetricsHistoryController {

    private final MetricsStorage metricsStorage;

    /**
     * 查询历史指标数据
     *
     * @param start    起始时间戳（毫秒），默认 1 小时前
     * @param end      结束时间戳（毫秒），默认当前时间
     * @param interval 聚合间隔（秒），0 表示不聚合
     */
    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> getHistory(
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(required = false, defaultValue = "0") int interval) {
        try {
            List<Map<String, Object>> data = metricsStorage.queryHistory(start, end, interval);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            log.error("查询历史指标失败", e);
            return ApiResponse.error("查询历史指标失败: " + e.getMessage());
        }
    }
}
