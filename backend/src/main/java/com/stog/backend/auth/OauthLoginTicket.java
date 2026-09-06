package com.stog.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "oauth_login_tickets")
public class OauthLoginTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(name = "provider_user_id", nullable = false, columnDefinition = "text")
    private String providerUserId;

    @Column(nullable = false, columnDefinition = "text")
    private String nickname;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected OauthLoginTicket() {
    }

    public OauthLoginTicket(
        String provider,
        String providerUserId,
        String nickname,
        String tokenHash,
        Instant expiresAt
    ) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.nickname = nickname;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isActive(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        consumedAt = now;
    }

    public SocialProfile profile() {
        return new SocialProfile(provider, providerUserId, nickname);
    }
}
