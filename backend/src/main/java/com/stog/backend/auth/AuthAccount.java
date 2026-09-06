package com.stog.backend.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Convert;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "auth_accounts",
    uniqueConstraints = @UniqueConstraint(
        name = "auth_accounts_provider_user_id_unique",
        columnNames = {"provider", "provider_user_id"}
    )
)
public class AuthAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Convert(converter = AuthProviderConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private AuthProvider provider;

    @Column(name = "provider_user_id", nullable = false, columnDefinition = "text")
    private String providerUserId;

    @Column(name = "password_hash", columnDefinition = "text")
    private String passwordHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AuthAccount() {
    }

    public AuthAccount(
        User user,
        AuthProvider provider,
        String providerUserId,
        String passwordHash
    ) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.passwordHash = passwordHash;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
