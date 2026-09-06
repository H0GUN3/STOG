package com.stog.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LocalAuthServiceTest {
    @Autowired
    private AuthService service;

    @Autowired
    private AuthAccountRepository accounts;

    @Test
    void signupNormalizesEmailAndRefreshRotatesOnce() {
        AuthSession signup = service.signup(
            " User@Example.COM ",
            "test-password",
            "테스트 사용자"
        );

        assertThat(accounts.findByProviderAndProviderUserId(
            "local",
            "user@example.com"
        )).isPresent();

        AuthSession login = service.login("USER@example.com", "test-password");
        assertThat(login.user().getId()).isEqualTo(signup.user().getId());

        AuthSession refreshed = service.refresh(login.refreshToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThatThrownBy(() -> service.refresh(login.refreshToken()))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void wrongPasswordIsRejected() {
        service.signup("wrong@example.com", "correct", "사용자");

        assertThatThrownBy(() -> service.login("wrong@example.com", "wrong"))
            .isInstanceOf(InvalidCredentialsException.class);
    }
}
