package com.lingframe.example.mall.controller.portal;

import com.lingframe.example.mall.dto.ResponseResult;
import com.lingframe.example.mall.security.SecurityUtils;
import com.lingframe.example.mall.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "6. 站内信与公告通知接口", description = "获取系统全局公告与个人私信，并标记已读")
@RestController
@RequestMapping("/api/portal/notifications")
@RequiredArgsConstructor
public class NotificationPortalController {

    private final NotificationService notificationService;

    @Operation(summary = "获取用户通知列表", description = "获取当前用户能收到的所有全局公告与私信，附带已读/未读状态")
    @GetMapping("/list")
    public ResponseResult<List<Map<String, Object>>> getNotifications() {
        Long userId = SecurityUtils.getUserId();
        List<Map<String, Object>> list = notificationService.getUserNotifications(userId);
        return ResponseResult.success(list);
    }

    @Operation(summary = "单条通知标为已读", description = "将指定通知ID标为已读")
    @PostMapping("/read/{notificationId}")
    public ResponseResult<Void> markAsRead(@PathVariable Long notificationId) {
        Long userId = SecurityUtils.getUserId();
        notificationService.markAsRead(userId, notificationId);
        return ResponseResult.success();
    }

    @Operation(summary = "全部通知标为已读", description = "一键将所有未读的公告和私信标为已读")
    @PostMapping("/read-all")
    public ResponseResult<Void> markAllAsRead() {
        Long userId = SecurityUtils.getUserId();
        notificationService.markAllAsRead(userId);
        return ResponseResult.success();
    }
}
