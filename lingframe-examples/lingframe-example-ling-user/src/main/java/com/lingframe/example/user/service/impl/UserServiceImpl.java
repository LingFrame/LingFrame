package com.lingframe.example.user.service.impl;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.security.Capabilities;
import com.lingframe.example.user.dto.UserDTO;
import com.lingframe.example.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 用户服务实现 - Dashboard 演示示例
 * 
 * 演示资源权限控制：
 * - DB 读取 (storage:sql READ)
 * - DB 写入 (storage:sql WRITE)
 * - 缓存读取 (cache:local READ)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final JdbcTemplate jdbcTemplate;

    // ==================== DB 读取 ====================

    /**
     * 查询用户（DB 读取）
     */
    @LingService(id = "query_user", desc = "根据ID查询用户")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 读取
    @Cacheable(value = "users", key = "#userId") // 同时演示缓存读取
    @Override
    public Optional<UserDTO> queryUser(String userId) {
        log.info("queryUser (cache miss), userId: {}", userId);
        String sql = "SELECT * FROM t_user WHERE id = ?";
        try {
            UserDTO user = jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(UserDTO.class), userId);
            return Optional.ofNullable(user);
        } catch (Exception e) {
            log.warn("User not found: {}", userId);
            return Optional.empty();
        }
    }

    /**
     * 列出所有用户（DB 读取）
     */
    @LingService(id = "list_users", desc = "列出所有用户")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 读取
    @Auditable(action = "LIST_USERS", resource = "user")
    @Override
    public List<UserDTO> listUsers() {
        log.info("listUsers");
        return jdbcTemplate.query("SELECT * FROM t_user", new BeanPropertyRowMapper<>(UserDTO.class));
    }

    // ==================== DB 写入 ====================

    /**
     * 创建用户（DB 写入）
     */
    @LingService(id = "create_user", desc = "创建新用户")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 写入
    @Auditable(action = "CREATE_USER", resource = "user")
    @Override
    public UserDTO createUser(String name, String email) {
        log.info("createUser, name: {}, email: {}", name, email);
        String sql = "INSERT INTO t_user (name, email) VALUES (?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[] { "id" });
            ps.setString(1, name);
            ps.setString(2, email);
            return ps;
        }, keyHolder);

        String generatedId = String.valueOf(keyHolder.getKey());
        return new UserDTO(generatedId, name, email);
    }

    /**
     * 删除用户（DB 写入）
     */
    @LingService(id = "delete_user", desc = "删除用户")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 写入
    @Auditable(action = "DELETE_USER", resource = "user")
    @Override
    public boolean deleteUser(String id) {
        log.info("deleteUser, id: {}", id);
        String sql = "DELETE FROM t_user WHERE id = ?";
        return jdbcTemplate.update(sql, id) > 0;
    }

    // ==================== 兼容接口 ====================

    /**
     * 更新用户（DB 写入）
     */
    @LingService(id = "update_user", desc = "更新用户信息")
    @RequiresPermission(Capabilities.STORAGE_SQL) // DB 写入
    @Auditable(action = "UPDATE_USER", resource = "user")
    @Override
    public UserDTO updateUser(String id, String name, String email) {
        log.info("updateUser, id: {}, name: {}, email: {}", id, name, email);
        String sql = "UPDATE t_user SET name = ?, email = ? WHERE id = ?";
        int updated = jdbcTemplate.update(sql, name, email, id);
        if (updated == 0) {
            log.warn("User not found for update: {}", id);
            return null;
        }
        return new UserDTO(id, name, email);
    }

    @Override
    public void saveUser(UserDTO userDTO) {
        createUser(userDTO.getName(), userDTO.getEmail());
    }
}