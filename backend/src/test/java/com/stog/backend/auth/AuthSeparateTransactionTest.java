package com.stog.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuthSeparateTransactionTest {
    @Autowired
    private AuthService auth;

    @Test
    void loginFindsAccountAfterSignupTransactionCommits() {
        String email = "transaction-probe-" + Instant.now().toEpochMilli() + "@example.com";
        AuthSession signup = auth.signup(email, "probe-password", "Transaction Probe");

        AuthSession login = auth.login(email.toUpperCase(), "probe-password");

        assertThat(login.user().getId()).isEqualTo(signup.user().getId());
    }
}
