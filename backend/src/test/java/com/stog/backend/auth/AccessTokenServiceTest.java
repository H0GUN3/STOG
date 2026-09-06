package com.stog.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AccessTokenServiceTest {
    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService service;

    @Autowired
    private JwtDecoder decoder;

    @Test
    void accessTokenExpiresAfterConfiguredFifteenMinutes() {
        User user = users.save(new User("JWT 테스트"));

        Jwt jwt = decoder.decode(service.issue(user));

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
            .isEqualTo(Duration.ofMinutes(15));
    }
}
