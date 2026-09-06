package com.stog.backend.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class NaverOAuthClient {
    private final String clientId;
    private final String clientSecret;
    private final String callbackUri;
    private final RestClient naver;
    private final RestClient profileApi;

    public NaverOAuthClient(
        @Value("${NAVER_CLIENT_ID}") String clientId,
        @Value("${NAVER_CLIENT_SECRET}") String clientSecret,
        @Value("${NAVER_REDIRECT_URI:}") String callbackUri,
        @Qualifier("socialRestClientBuilder") RestClient.Builder builder
    ) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.callbackUri = callbackUri;
        this.naver = builder.baseUrl("https://nid.naver.com").build();
        this.profileApi = builder.baseUrl("https://openapi.naver.com").build();
    }

    public String authorizeUri(String state) {
        if (callbackUri.isBlank()) {
            throw new IllegalStateException("NAVER_REDIRECT_URI is not configured");
        }
        return UriComponentsBuilder
            .fromUriString("https://nid.naver.com/oauth2.0/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", callbackUri)
            .queryParam("state", state)
            .build()
            .encode()
            .toUriString();
    }

    public String exchangeCode(String code, String state) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", code);
        body.add("state", state);
        NaverTokenResponse response = naver.post()
            .uri("/oauth2.0/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(body)
            .retrieve()
            .body(NaverTokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new InvalidCredentialsException();
        }
        return response.accessToken();
    }

    public SocialProfile profile(String accessToken) {
        NaverProfileResponse response = profileApi.get()
            .uri("/v1/nid/me")
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve()
            .body(NaverProfileResponse.class);
        if (response == null || response.response() == null
            || response.response().id() == null) {
            throw new InvalidCredentialsException();
        }
        String nickname = response.response().nickname();
        return new SocialProfile(
            "naver",
            response.response().id(),
            nickname == null || nickname.isBlank() ? "Naver user" : nickname
        );
    }

    public record NaverTokenResponse(
        @JsonProperty("access_token") String accessToken
    ) {
    }

    public record NaverProfileResponse(Profile response) {
        public record Profile(String id, String nickname) {
        }
    }
}
