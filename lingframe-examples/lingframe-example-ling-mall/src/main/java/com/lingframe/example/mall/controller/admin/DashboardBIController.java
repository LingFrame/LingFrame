package com.lingframe.example.mall.controller.admin;

import com.lingframe.example.mall.dto.ResponseResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "10. 后台BI经营大盘 (Admin)", description = "提供GMV、客单价、分类销售额分布及热销Top10数据可视化统计")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardBIController {

    private final JdbcTemplate jdbcTemplate;

    @Operation(summary = "BI经营看板核心指标", description = "计算累计实收GMV、有效订单数以及平均客单价 (ROLE_ADMIN专享)")
    @GetMapping("/metrics")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseResult<Map<String, Object>> getMetrics() {
        // 统计 GMV: 已支付、已发货、已签收、售后退款中状态的订单
        String gmvSql = "SELECT SUM(total_amount) FROM t_order WHERE status IN (1, 2, 3, 5)";
        BigDecimal gmv = jdbcTemplate.queryForObject(gmvSql, BigDecimal.class);
        if (gmv == null) gmv = BigDecimal.ZERO;

        String orderCountSql = "SELECT COUNT(1) FROM t_order WHERE status IN (1, 2, 3, 5)";
        Long count = jdbcTemplate.queryForObject(orderCountSql, Long.class);
        if (count == null) count = 0L;

        BigDecimal aov = BigDecimal.ZERO;
        if (count > 0) {
            aov = gmv.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP);
        }

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("gmv", gmv);
        metrics.put("validOrderCount", count);
        metrics.put("averageOrderValue", aov);
        return ResponseResult.success(metrics);
    }

    @Operation(summary = "商品大类销量分布占比", description = "按SPU分类统计起购量占比情况 (ROLE_ADMIN专享)")
    @GetMapping("/category-ratio")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseResult<List<Map<String, Object>>> getCategoryRatio() {
        String sql = "SELECT s.category as name, SUM(i.quantity) as value " +
                "FROM t_order_item i " +
                "INNER JOIN t_order o ON i.order_id = o.id " +
                "INNER JOIN t_spu s ON i.spu_id = s.id " +
                "WHERE o.status IN (1, 2, 3, 5) " +
                "GROUP BY s.category";
        List<Map<String, Object>> ratio = jdbcTemplate.queryForList(sql);
        return ResponseResult.success(ratio);
    }

    @Operation(summary = "热销SKU型号排行榜Top10", description = "按销量降序排列前10名具体SKU (ROLE_ADMIN专享)")
    @GetMapping("/top-products")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseResult<List<Map<String, Object>>> getTopProducts() {
        String sql = "SELECT i.product_name as name, SUM(i.quantity) as value " +
                "FROM t_order_item i " +
                "INNER JOIN t_order o ON i.order_id = o.id " +
                "WHERE o.status IN (1, 2, 3, 5) " +
                "GROUP BY i.sku_id, i.product_name " +
                "ORDER BY value DESC " +
                "LIMIT 10";
        List<Map<String, Object>> topList = jdbcTemplate.queryForList(sql);
        return ResponseResult.success(topList);
    }
}
