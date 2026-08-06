package com.lingframe.example.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lingframe.example.mall.entity.Notification;
import com.lingframe.example.mall.entity.NotificationRead;
import com.lingframe.example.mall.mapper.NotificationMapper;
import com.lingframe.example.mall.mapper.NotificationReadMapper;
import com.lingframe.example.mall.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification> implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationReadMapper notificationReadMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<Map<String, Object>> getUserNotifications(Long userId) {
        // 利用轻量 SQL 查询用户能看见的全部公告(type=0)和私信(type=1, receiver=userId)，并关联已读状态
        String sql = "SELECT n.id, n.title, n.content, n.type, n.created_at, " +
                "CASE WHEN nr.id IS NOT NULL THEN 1 ELSE 0 END as is_read " +
                "FROM t_notification n " +
                "LEFT JOIN t_notification_read nr ON n.id = nr.notification_id AND nr.user_id = ? " +
                "WHERE n.type = 0 OR (n.type = 1 AND n.receiver_id = ?) " +
                "ORDER BY n.created_at DESC";
        return jdbcTemplate.queryForList(sql, userId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsRead(Long userId, Long notificationId) {
        Long count = notificationReadMapper.selectCount(new LambdaQueryWrapper<NotificationRead>()
                .eq(NotificationRead::getNotificationId, notificationId)
                .eq(NotificationRead::getUserId, userId));
        
        if (count == 0) {
            NotificationRead nr = new NotificationRead();
            nr.setNotificationId(notificationId);
            nr.setUserId(userId);
            nr.setIsRead(1);
            nr.setReadAt(new Date());
            notificationReadMapper.insert(nr);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllAsRead(Long userId) {
        // 获取当前用户的所有可读通知
        List<Notification> list = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, 0)
                .or(w -> w.eq(Notification::getType, 1).eq(Notification::getReceiverId, userId)));
        
        for (Notification n : list) {
            markAsRead(userId, n.getId());
        }
    }
}
