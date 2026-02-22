package com.lingframe.example.user.canary.service;

import com.lingframe.example.user.canary.dto.UserDTO;

import java.util.List;
import java.util.Optional;

/**
 * 用户服务接口 - 金丝雀版本
 */
public interface UserService {

    Optional<UserDTO> queryUser(String userId);

    List<UserDTO> listUsers();

    UserDTO createUser(String name, String email);

    UserDTO updateUser(String id, String name, String email);

    boolean deleteUser(String id);

    void saveUser(UserDTO userDTO);
    
    // ========== 金丝雀版本新增方法 ==========
    
    /**
     * 批量查询用户 (实验性功能)
     */
    List<UserDTO> batchQueryUsers(List<String> userIds);
    
    /**
     * 更新用户状态 (实验性功能)
     */
    boolean updateUserStatus(String userId, String status);
}
