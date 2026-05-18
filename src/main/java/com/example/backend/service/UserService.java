package com.example.backend.service;

import com.example.backend.model.User;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {

    private final List<User> users = new ArrayList<>();

    public List<User> getUsers() {
        return users;
    }

    public User createUser(User user) {
        users.add(user);
        return user;
    }
}
