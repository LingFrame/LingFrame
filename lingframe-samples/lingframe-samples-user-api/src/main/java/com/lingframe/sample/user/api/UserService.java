package com.lingframe.sample.user.api;

import com.lingframe.sample.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface UserService {

    Optional<User> queryUser(String userId);

    List<User> listUsers();

    User createUser(String name, String email);

    User updateUser(String id, String name, String email);

    boolean deleteUser(String id);

    void saveUser(User user);
}
