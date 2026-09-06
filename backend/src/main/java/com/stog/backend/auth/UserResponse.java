package com.stog.backend.auth;

public record UserResponse(Long id, String nickname) {
    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getNickname());
    }
}
