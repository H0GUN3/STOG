package com.stog.backend.auth;

import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    public AccessTokenService(
        JwtEncoder encoder,
        AuthProperties properties,
        Clock clock
    ) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("stog")
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTokenTtl()))
            .subject(user.getId().toString())
            .claim("token_type", "access")
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims))
            .getTokenValue();
    }
}
