package com.stog.backend.auth;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class GoogleProfileVerifier implements SocialProfileVerifier {
    private final String clientId;
    private volatile JwtDecoder decoder;

    public GoogleProfileVerifier(
        @Value("${stog.google.web-client-id}")
        String clientId
    ) {
        this.clientId = clientId;
    }

    @Override
    public SocialProfile verify(String token) {
        if (clientId.isBlank()) {
            throw new InvalidCredentialsException();
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(token);
        } catch (JwtException error) {
            throw new InvalidCredentialsException();
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidCredentialsException();
        }
        String nickname = jwt.getClaimAsString("name");
        if (nickname == null || nickname.isBlank()) {
            nickname = jwt.getClaimAsString("email");
        }
        return new SocialProfile("google", subject, nickname);
    }

    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (decoder == null) {
                NimbusJwtDecoder created = (NimbusJwtDecoder) JwtDecoders
                    .fromIssuerLocation("https://accounts.google.com");
                OAuth2TokenValidator<Jwt> issuer = JwtValidators
                    .createDefaultWithIssuer("https://accounts.google.com");
                OAuth2TokenValidator<Jwt> audience = jwt -> {
                    List<String> values = jwt.getAudience();
                    return values.contains(clientId)
                        ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                        : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                            new org.springframework.security.oauth2.core.OAuth2Error(
                                "invalid_token",
                                "Google token audience is invalid",
                                null
                            )
                        );
                };
                created.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
                decoder = created;
            }
            return decoder;
        }
    }
}
