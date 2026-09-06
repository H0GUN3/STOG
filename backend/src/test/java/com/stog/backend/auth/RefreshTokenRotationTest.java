package com.stog.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class RefreshTokenRotationTest {
    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private RefreshTokenService service;

    @Autowired
    private TokenHasher hasher;

    @Test
    void rotatingRevokesPreviousTokenAndRejectsItsReuse() {
        User user = users.save(new User("테스트 사용자"));
        RefreshTokenService.IssuedToken first = service.issue(user);

        RefreshTokenService.IssuedToken second = service.rotate(first.value());

        RefreshToken previous = refreshTokens.findByTokenHash(hasher.hash(first.value()))
            .orElseThrow();
        RefreshToken replacement = refreshTokens.findByTokenHash(hasher.hash(second.value()))
            .orElseThrow();
        assertThat(previous.getRevokedAt()).isNotNull();
        assertThat(previous.getReplacedBy()).isEqualTo(replacement);

        assertThatThrownBy(() -> service.rotate(first.value()))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void replayingAnOlderTokenRevokesItsEntireReplacementChain() {
        User user = users.save(new User("재사용 테스트 사용자"));
        RefreshTokenService.IssuedToken first = service.issue(user);
        RefreshTokenService.IssuedToken second = service.rotate(first.value());
        RefreshTokenService.IssuedToken third = service.rotate(second.value());

        assertThatThrownBy(() -> service.rotate(first.value()))
            .isInstanceOf(InvalidRefreshTokenException.class);

        RefreshToken replacement = refreshTokens.findByTokenHash(hasher.hash(second.value()))
            .orElseThrow();
        RefreshToken descendant = refreshTokens.findByTokenHash(hasher.hash(third.value()))
            .orElseThrow();
        assertThat(replacement.getRevokedAt()).isNotNull();
        assertThat(descendant.getRevokedAt()).isNotNull();
    }
}
