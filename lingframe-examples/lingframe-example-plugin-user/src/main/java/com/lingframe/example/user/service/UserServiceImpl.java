package com.lingframe.example.user.service;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.example.user.api.UserService;
import com.lingframe.example.user.dto.UserDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UserServiceImpl implements UserService {

    private final ConcurrentHashMap<String, UserDTO> userDatabase = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public UserServiceImpl() {
        // 初始化一些示例数据
        saveUser(new UserDTO("1", "Alice", "alice@example.com"));
        saveUser(new UserDTO("2", "Bob", "bob@example.com"));
    }

    @LingService(id = "query_user", desc = "根据ID查询用户")
    @Override
    public Optional<UserDTO> queryUser(String userId) {
        return Optional.ofNullable(userDatabase.get(userId));
    }

    @LingService(id = "list_users", desc = "列出所有用户")
    @Override
    public List<UserDTO> listUsers() {
        return new ArrayList<>(userDatabase.values());
    }

    @LingService(id = "create_user", desc = "创建新用户")
    @RequiresPermission("user:write")
    @Auditable(action = "CREATE_USER", resource = "user")
    @Override
    public UserDTO createUser(String name, String email) {
        String id = String.valueOf(idGenerator.getAndIncrement());
        UserDTO userDTO = new UserDTO(id, name, email);
        userDatabase.put(id, userDTO);
        return userDTO;
    }

    @LingService(id = "update_user", desc = "更新用户信息")
    @RequiresPermission("user:write")
    @Auditable(action = "UPDATE_USER", resource = "user")
    @Override
    public UserDTO updateUser(String id, String name, String email) {
        UserDTO userDTO = userDatabase.get(id);
        if (userDTO != null) {
            userDTO.setName(name);
            userDTO.setEmail(email);
            userDatabase.put(id, userDTO);
        }
        return userDTO;
    }

    @LingService(id = "delete_user", desc = "删除用户")
    @RequiresPermission("user:delete")
    @Auditable(action = "DELETE_USER", resource = "user")
    @Override
    public boolean deleteUser(String id) {
        return userDatabase.remove(id) != null;
    }

    @Override
    public void saveUser(UserDTO userDTO) {
        userDatabase.put(userDTO.getId(), userDTO);
    }
}