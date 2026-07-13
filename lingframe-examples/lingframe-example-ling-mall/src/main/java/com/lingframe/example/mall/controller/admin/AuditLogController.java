package com.lingframe.example.mall.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.entity.AuditRecord;
import com.lingframe.example.mall.mapper.AuditRecordMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "9. 后台审计日志管理 (Admin)", description = "系统操作审计追踪")
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditRecordMapper auditRecordMapper;

    @Operation(summary = "审计日志列表", description = "获取系统全局操作审计日志 (ROLE_ADMIN专享)")
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('audit:admin:list')")
    public ResponseResult<List<AuditRecord>> getList(@RequestParam(required = false) String action) {
        List<AuditRecord> list = auditRecordMapper.selectList(new LambdaQueryWrapper<AuditRecord>()
                .eq(action != null, AuditRecord::getAction, action)
                .orderByDesc(AuditRecord::getCreatedAt));
        return ResponseResult.success(list);
    }
}
