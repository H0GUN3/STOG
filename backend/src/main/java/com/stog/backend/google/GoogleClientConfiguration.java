package com.stog.backend.google;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GoogleClientConfiguration {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    RestClient.Builder googleRestClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
        JdkClientHttpRequestFactory requestFactory =
            new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(HTTP_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
