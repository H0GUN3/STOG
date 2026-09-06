package com.stog.backend.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class GoogleClientConfigurationTest {
    @Test
    void googleClientsUseThreeSecondConnectionAndReadTimeouts() {
        RestClient client = new GoogleClientConfiguration()
            .googleRestClientBuilder()
            .build();

        Object requestFactory = ReflectionTestUtils.getField(
            client,
            "clientRequestFactory"
        );
        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);

        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(
            requestFactory,
            "httpClient"
        );
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(3));
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout"))
            .isEqualTo(Duration.ofSeconds(3));
    }
}
