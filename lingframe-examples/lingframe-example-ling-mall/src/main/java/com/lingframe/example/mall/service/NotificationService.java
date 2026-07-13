package com.lingframe.example.mall.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lingframe.example.mall.entity.Notification;

import java.util.List;
import java.util.Map;

public interface NotificationService extends IService<Notification> {

    List<Map<String, Object>> getUserNotifications(Long userId);

    void markAsRead(Long userId, Long notificationId);

    void markAllAsRead(Long userId);
}
