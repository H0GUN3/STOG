package com.stog.backend.ai;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import com.stog.backend.place.PlaceRequests;
import com.stog.backend.place.search.PlaceSearchProvenance;
import com.stog.backend.place.search.PlaceSearchResult;
import com.stog.backend.place.search.PlaceSearchService;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class StobeeChatService {
    private static final String EMPTY_MESSAGE =
        "지금은 답변을 준비하지 못했어요. 잠시 후 다시 시도해 주세요.";

    static String normalizedMessage(String message) {
        if (message == null || message.isBlank() || "null".equalsIgnoreCase(message.trim())) {
            return EMPTY_MESSAGE;
        }
        return message;
    }

    @Bean
    @ConfigurationProperties(prefix = "stog.ai")
    AiProperties aiProperties() {
        return new AiProperties();
    }

    @Bean
    @Qualifier("stogAiRestClientBuilder")
    RestClient.Builder stogAiRestClientBuilder(AiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout)
            .build();
        JdkClientHttpRequestFactory requestFactory =
            new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout);
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    CloudRunIdTokenProvider cloudRunIdTokenProvider() {
        return new CloudRunIdTokenProvider();
    }

    @Bean
    StogAiClient stogAiClient(
        @Qualifier("stogAiRestClientBuilder") RestClient.Builder builder,
        AiProperties properties,
        CloudRunIdTokenProvider tokens,
        PlaceSearchService places
    ) {
        return new StogAiClient(builder, properties, tokens, places);
    }

    static final class AiProperties {
        private String baseUrl = "";
        private Duration timeout = Duration.ofSeconds(30);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    static final class CloudRunIdTokenProvider {
        private volatile IdTokenProvider provider;

        String tokenFor(String audience) {
            try {
                return provider().idTokenWithAudience(audience, List.of()).getTokenValue();
            } catch (IOException error) {
                throw new IllegalStateException(
                    "Unable to create Cloud Run ID token",
                    error
                );
            }
        }

        private IdTokenProvider provider() throws IOException {
            IdTokenProvider current = provider;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (provider == null) {
                    GoogleCredentials credentials =
                        GoogleCredentials.getApplicationDefault();
                    if (!(credentials instanceof IdTokenProvider idTokenProvider)) {
                        throw new IllegalStateException(
                            "Application credentials do not support Cloud Run ID tokens"
                        );
                    }
                    provider = idTokenProvider;
                }
                return provider;
            }
        }
    }

    static final class StogAiClient {
        private final RestClient client;
        private final AiProperties properties;
        private final CloudRunIdTokenProvider tokens;
        private final PlaceSearchService places;

        StogAiClient(
            @Qualifier("stogAiRestClientBuilder") RestClient.Builder builder,
            AiProperties properties,
            CloudRunIdTokenProvider tokens,
            PlaceSearchService places
        ) {
            this.client = builder.build();
            this.properties = properties;
            this.tokens = tokens;
            this.places = places;
        }

        StobeeChatController.ChatResponse chat(
            StobeeChatController.ChatRequest request
        ) {
            if (properties.baseUrl.isBlank()) {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "STOG AI service is not configured"
                );
            }
            try {
                StobeeChatController.ChatResponse qwenResponse = client.post()
                    .uri(properties.baseUrl.replaceAll("/+$", "") + "/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                        "Authorization",
                        "Bearer " + tokens.tokenFor(properties.baseUrl)
                    )
                    .body(request)
                    .retrieve()
                    .body(StobeeChatController.ChatResponse.class);
                if (qwenResponse == null) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "STOG AI service returned an empty response"
                    );
                }
                return new StobeeChatController.ChatResponse(
                    qwenResponse.session_id(),
                    normalizedMessage(qwenResponse.message()),
                    qwenResponse.user_understanding(),
                    qwenResponse.current_trip_context(),
                    recommendationsFor(request.message())
                );
            } catch (RestClientResponseException error) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "STOG AI service returned an error",
                    error
                );
            } catch (RestClientException error) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "STOG AI service is unavailable",
                    error
                );
            }
        }

        private List<StobeeChatController.PlaceRecommendation> recommendationsFor(
            String message
        ) {
            String query = message.contains("전북") ? message : "전북 " + message;
            List<PlaceSearchResult> results = places.search(new PlaceRequests.Search(
                query,
                null,
                null,
                null,
                5
            )).candidates();
            if (results.size() < 5 && !"전북 여행지".equals(query)) {
                java.util.Map<String, PlaceSearchResult> unique =
                    new java.util.LinkedHashMap<>();
                results.forEach(result ->
                    unique.put(result.provider() + ":" + result.external_id(), result)
                );
                places.search(new PlaceRequests.Search(
                    "전북 여행지",
                    null,
                    null,
                    null,
                    5
                )).candidates().forEach(result ->
                    unique.putIfAbsent(result.provider() + ":" + result.external_id(), result)
                );
                results = unique.values().stream().limit(5).toList();
            }
            if (results.size() != 5) {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "STOG place catalog returned fewer than five recommendations"
                );
            }
            return results.stream()
                .limit(5)
                .map(this::recommendation)
                .toList();
        }

        private StobeeChatController.PlaceRecommendation recommendation(
            PlaceSearchResult result
        ) {
            PlaceSearchProvenance provenance = result.provenance();
            return new StobeeChatController.PlaceRecommendation(
                provenance.place_id(),
                result.provider(),
                result.external_id(),
                result.name(),
                result.formatted_address(),
                result.latitude(),
                result.longitude(),
                result.types(),
                result.photo_urls(),
                provenance.source_type(),
                provenance.source_id(),
                provenance.catalog_status()
            );
        }
    }
}
