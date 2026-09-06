package com.stog.backend.auth;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository repository;
    private final TokenHasher hasher;
    private final RandomTokenGenerator generator;
    private final AuthProperties properties;
    private final Clock clock;

    public record IssuedToken(String value, Instant expiresAt, User user) {
    }

    public RefreshTokenService(
        RefreshTokenRepository repository,
        TokenHasher hasher,
        RandomTokenGenerator generator,
        AuthProperties properties,
        Clock clock
    ) {
        this.repository = repository;
        this.hasher = hasher;
        this.generator = generator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedToken issue(User user) {
        String value = generator.generate();
        Instant expiresAt = clock.instant().plus(properties.refreshTokenTtl());
        repository.save(new RefreshToken(user, hasher.hash(value), expiresAt));
        return new IssuedToken(value, expiresAt, user);
    }

    @Transactional
    public IssuedToken rotate(String value) {
        Instant now = clock.instant();
        RefreshToken previous = repository.findByTokenHash(hasher.hash(value))
            .orElseThrow(InvalidRefreshTokenException::new);
        if (!previous.isActive(now)) {
            revokeReplacementChain(previous, now);
            throw new InvalidRefreshTokenException();
        }

        String replacementValue = generator.generate();
        Instant expiresAt = now.plus(properties.refreshTokenTtl());
        RefreshToken replacement = repository.save(
            new RefreshToken(previous.getUser(), hasher.hash(replacementValue), expiresAt)
        );
        previous.revoke(now, replacement);
        repository.save(previous);
        return new IssuedToken(replacementValue, expiresAt, previous.getUser());
    }

    private void revokeReplacementChain(RefreshToken token, Instant now) {
        RefreshToken replacement = token.getReplacedBy();
        while (replacement != null) {
            if (replacement.isActive(now)) {
                replacement.revoke(now, null);
                repository.save(replacement);
            }
            replacement = replacement.getReplacedBy();
        }
    }

    @Transactional
    public void revoke(String value) {
        Instant now = clock.instant();
        RefreshToken token = repository.findByTokenHash(hasher.hash(value))
            .filter(candidate -> candidate.isActive(now))
            .orElseThrow(InvalidRefreshTokenException::new);
        token.revoke(now, null);
        repository.save(token);
    }
}
