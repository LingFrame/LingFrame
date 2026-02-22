package com.lingframe.example.user.canary.service.impl;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.security.Capabilities;
import com.lingframe.example.user.canary.dto.UserDTO;
import com.lingframe.example.user.canary.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户服务实现 - 金丝雀版本
 * 
 * 包含实验性功能：
 * - 批量查询用户
 * - 用户状态管理
 * - 增强的输入验证
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final JdbcTemplate jdbcTemplate;

    // ==================== 基础功能 (与稳定版相同) ====================

    @LingService(id = "canary_query_user", desc = "根据ID查询用户 (金丝雀)")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Cacheable(value = "users-canary", key = "#userId")
    @Override
    public Optional<UserDTO> queryUser(String userId) {
        log.info("[Canary] queryUser, userId: {}", userId);
        validateInput(userId, "userId");
        
        String sql = "SELECT * FROM t_user WHERE id = ?";
        try {
            UserDTO user = jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(UserDTO.class), userId);
            return Optional.ofNullable(user);
        } catch (Exception e) {
            log.warn("[Canary] User not found: {}", userId);
            return Optional.empty();
        }
    }

    @LingService(id = "canary_list_users", desc = "列出所有用户 (金丝雀)")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "LIST_USERS", resource = "user-canary")
    @Override
    public List<UserDTO> listUsers() {
        log.info("[Canary] listUsers");
        return jdbcTemplate.query("SELECT * FROM t_user", new BeanPropertyRowMapper<>(UserDTO.class));
    }

    @LingService(id = "canary_create_user", desc = "创建新用户 (金丝雀)")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "CREATE_USER", resource = "user-canary")
    @Override
    public UserDTO createUser(String name, String email) {
        log.info("[Canary] createUser, name: {}, email: {}", name, email);
        validateInput(name, "name");
        validateInput(email, "email");
        
        String sql = "INSERT INTO t_user (name, email) VALUES (?, ?)";
        
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, email);
            return ps;
        }, keyHolder);
        
        String generatedId = String.valueOf(keyHolder.getKey());
        return new UserDTO(generatedId, name, email);
    }

    @LingService(id = "canary_update_user", desc = "更新用户信息 (金丝雀)")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "UPDATE_USER", resource = "user-canary")
    @Override
    public UserDTO updateUser(String id, String name, String email) {
        log.info("[Canary] updateUser, id: {}, name: {}, email: {}", id, name, email);
        validateInput(id, "id");
        
        String sql = "UPDATE t_user SET name = ?, email = ? WHERE id = ?";
        int updated = jdbcTemplate.update(sql, name, email, id);
        if (updated == 0) {
            log.warn("[Canary] User not found for update: {}", id);
            return null;
        }
        return new UserDTO(id, name, email);
    }

    @LingService(id = "canary_delete_user", desc = "删除用户 (金丝雀)")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "DELETE_USER", resource = "user-canary")
    @Override
    public boolean deleteUser(String id) {
        log.info("[Canary] deleteUser, id: {}", id);
        validateInput(id, "id");
        
        String sql = "DELETE FROM t_user WHERE id = ?";
        return jdbcTemplate.update(sql, id) > 0;
    }

    @Override
    public void saveUser(UserDTO userDTO) {
        createUser(userDTO.getName(), userDTO.getEmail());
    }

    // ==================== 金丝雀版本新增功能 ====================

    /**
     * 批量查询用户 (实验性功能)
     */
    @LingService(id = "canary_batch_query_users", desc = "批量查询用户 (实验性)")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Override
    public List<UserDTO> batchQueryUsers(List<String> userIds) {
        log.info("[Canary] batchQueryUsers, count: {}", userIds.size());
        
        if (userIds == null || userIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        
        String sql = "SELECT * FROM t_user WHERE id IN (" + placeholders + ")";
        return jdbcTemplate.query(sql, new BeanPropertyRowMapper<>(UserDTO.class), userIds.toArray());
    }

    /**
     * 更新用户状态 (实验性功能)
     */
    @LingService(id = "canary_update_user_status", desc = "更新用户状态 (实验性)")
    @RequiresPermission(Capabilities.STORAGE_SQL)
    @Auditable(action = "UPDATE_USER_STATUS", resource = "user-canary")
    @Override
    public boolean updateUserStatus(String userId, String status) {
        log.info("[Canary] updateUserStatus, userId: {}, status: {}", userId, status);
        validateInput(userId, "userId");
        validateInput(status, "status");
        
        // 注意：这需要 t_user 表有 status 字段
        // 这是金丝雀版本的实验性功能
        String sql = "UPDATE t_user SET status = ? WHERE id = ?";
        return jdbcTemplate.update(sql, status, userId) > 0;
    }

    // ==================== 增强的输入验证 (实验性功能) ====================

    private void validateInput(String input, String fieldName) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("[Canary] " + fieldName + " cannot be null or empty");
        }
    }
}
