package com.lingframe.sample.user;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.LingService;
import com.lingframe.api.annotation.RequiresPermission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UserService {

    private final ConcurrentHashMap<String, User> userDatabase = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public UserService() {
        // 初始化一些示例数据
        saveUser(new User("1", "Alice", "alice@example.com"));
        saveUser(new User("2", "Bob", "bob@example.com"));
    }

    @LingService(id = "query_user", desc = "根据ID查询用户")
    public Optional<User> queryUser(String userId) {
        return Optional.ofNullable(userDatabase.get(userId));
    }

    @LingService(id = "list_users", desc = "列出所有用户")
    public List<User> listUsers() {
        return new ArrayList<>(userDatabase.values());
    }

    @LingService(id = "create_user", desc = "创建新用户")
    @RequiresPermission("user:write")
    @Auditable(action = "CREATE_USER", resource = "user")
    public User createUser(String name, String email) {
        String id = String.valueOf(idGenerator.getAndIncrement());
        User user = new User(id, name, email);
        userDatabase.put(id, user);
        return user;
    }

    @LingService(id = "update_user", desc = "更新用户信息")
    @RequiresPermission("user:write")
    @Auditable(action = "UPDATE_USER", resource = "user")
    public User updateUser(String id, String name, String email) {
        User user = userDatabase.get(id);
        if (user != null) {
            user.setName(name);
            user.setEmail(email);
            userDatabase.put(id, user);
        }
        return user;
    }

    @LingService(id = "delete_user", desc = "删除用户")
    @RequiresPermission("user:delete")
    @Auditable(action = "DELETE_USER", resource = "user")
    public boolean deleteUser(String id) {
        return userDatabase.remove(id) != null;
    }

    private void saveUser(User user) {
        userDatabase.put(user.getId(), user);
    }
}