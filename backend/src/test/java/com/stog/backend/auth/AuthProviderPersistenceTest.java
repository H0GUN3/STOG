package com.stog.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class AuthProviderPersistenceTest {
    @Autowired
    private AuthService auth;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void localProviderPersistsInCanonicalLowercase() {
        String email = "provider-probe-" + Instant.now().toEpochMilli() + "@example.com";
        auth.signup(email, "probe-password", "Provider Probe");

        String provider = jdbc.sql(
                "SELECT provider FROM auth_accounts WHERE provider_user_id = :email"
            )
            .param("email", email)
            .query(String.class)
            .single();

        assertThat(provider).isEqualTo("local");
    }
}
