package com.stog.backend.auth;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class KakaoProfileVerifier implements SocialProfileVerifier {
    private final RestClient client;

    public KakaoProfileVerifier(
        @Qualifier("socialRestClientBuilder") RestClient.Builder builder
    ) {
        client = builder.baseUrl("https://kapi.kakao.com").build();
    }

    @Override
    public SocialProfile verify(String token) {
        Map<?, ?> profile;
        try {
            profile = client.get()
                .uri("/v2/user/me")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(Map.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().is4xxClientError()) {
                throw new InvalidCredentialsException();
            }
            throw error;
        }
        if (profile == null || profile.get("id") == null) {
            throw new InvalidCredentialsException();
        }

        String nickname = nickname(profile);
        return new SocialProfile("kakao", profile.get("id").toString(), nickname);
    }

    private String nickname(Map<?, ?> profile) {
        Object properties = profile.get("properties");
        if (properties instanceof Map<?, ?> values
            && values.get("nickname") instanceof String nickname
            && !nickname.isBlank()) {
            return nickname;
        }
        return "Kakao user";
    }
}
