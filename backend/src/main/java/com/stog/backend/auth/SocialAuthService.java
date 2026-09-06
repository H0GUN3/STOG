package com.stog.backend.auth;

import org.springframework.stereotype.Service;

@Service
public class SocialAuthService {
    private final SocialProfileVerifier kakao;
    private final SocialProfileVerifier google;
    private final LoginTicketService tickets;
    private final AuthService auth;

    public SocialAuthService(
        KakaoProfileVerifier kakao,
        GoogleProfileVerifier google,
        LoginTicketService tickets,
        AuthService auth
    ) {
        this.kakao = kakao;
        this.google = google;
        this.tickets = tickets;
        this.auth = auth;
    }

    public AuthSession login(String provider, String token) {
        return switch (provider.toLowerCase(java.util.Locale.ROOT)) {
            case "kakao" -> auth.social(kakao.verify(token));
            case "google" -> auth.social(google.verify(token));
            case "naver" -> auth.social(tickets.consume(token));
            default -> throw new InvalidCredentialsException();
        };
    }
}
