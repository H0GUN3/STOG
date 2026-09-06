package com.stog.backend.auth;

import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    public User find(Long userId) {
        return users.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException());
    }
}
