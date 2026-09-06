package com.stog.backend.auth;

import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final AuthAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;

    public AuthService(
        UserRepository users,
        AuthAccountRepository accounts,
        PasswordEncoder passwords,
        AccessTokenService accessTokens,
        RefreshTokenService refreshTokens
    ) {
        this.users = users;
        this.accounts = accounts;
        this.passwords = passwords;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public AuthSession signup(String email, String password, String nickname) {
        String normalizedEmail = normalizeEmail(email);
        if (accounts.existsByProviderAndProviderUserId(
            "local",
            normalizedEmail
        )) {
            throw new DuplicateAccountException();
        }

        User user = users.save(new User(nickname));
        accounts.save(new AuthAccount(
            user,
            AuthProvider.LOCAL,
            normalizedEmail,
            passwords.encode(password)
        ));
        return issueSession(user);
    }

    @Transactional
    public AuthSession login(String email, String password) {
        Optional<AuthAccount> found = accounts.findByProviderAndProviderUserId(
            "local",
            normalizeEmail(email)
        );
        AuthAccount account = found.orElseThrow(InvalidCredentialsException::new);
        if (!passwords.matches(password, account.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueSession(account.getUser());
    }

    @Transactional
    public AuthSession social(SocialProfile profile) {
        AuthAccount account = accounts.findByProviderAndProviderUserId(
                profile.provider(),
                profile.providerUserId()
            )
            .orElseGet(() -> {
                User user = users.save(new User(profile.nickname()));
                return accounts.save(new AuthAccount(
                    user,
                    AuthProvider.valueOf(profile.provider().toUpperCase()),
                    profile.providerUserId(),
                    null
                ));
            });
        return issueSession(account.getUser());
    }

    @Transactional
    public AuthSession refresh(String refreshToken) {
        RefreshTokenService.IssuedToken rotated = refreshTokens.rotate(refreshToken);
        User user = initialize(rotated.user());
        return new AuthSession(
            accessTokens.issue(user),
            rotated.value(),
            user
        );
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    private AuthSession issueSession(User user) {
        initialize(user);
        RefreshTokenService.IssuedToken refresh = refreshTokens.issue(user);
        return new AuthSession(
            accessTokens.issue(user),
            refresh.value(),
            user
        );
    }

    private User initialize(User user) {
        user.getId();
        user.getNickname();
        return user;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
