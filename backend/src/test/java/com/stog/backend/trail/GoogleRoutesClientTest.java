package com.stog.backend.trail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;

import com.stog.backend.google.GoogleProviderException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GoogleRoutesClientTest {
    @Test
    void computeMapsFirstRouteSummary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleRoutesClient client = new GoogleRoutesClient(
            builder,
            new GoogleRoutesProperties(
                "routes-key",
                URI.create("https://example.test")
            ),
            new ObjectMapper()
        );
        server.expect(requestTo(
                "https://example.test/directions/v2:computeRoutes"
            ))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Goog-Api-Key", "routes-key"))
            .andExpect(request -> assertThat(
                request.getHeaders().getFirst("X-Goog-FieldMask")
            ).isNotBlank())
            .andRespond(withSuccess(
                """
                {
                  "routes": [{
                    "duration": "900s",
                    "distanceMeters": 1200,
                    "polyline": {"encodedPolyline": "abc123"}
                  }]
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        TrailResult result = client.compute(new TrailRequests.Compute(
            new TrailRequests.Coordinate(35.815, 127.15),
            new TrailRequests.Coordinate(35.82, 127.16),
            List.of(),
            TrailRequests.TravelMode.WALK
        ));

        assertThat(result.duration()).isEqualTo("900s");
        assertThat(result.distance_meters()).isEqualTo(1200L);
        assertThat(result.encoded_polyline()).isEqualTo("abc123");
        server.verify();
    }

    @Test
    void missingApiKeyFailsBeforeNetworkAccess() {
        GoogleRoutesClient client = new GoogleRoutesClient(
            RestClient.builder(),
            new GoogleRoutesProperties(
                "",
                URI.create("https://example.test")
            ),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> client.compute(new TrailRequests.Compute(
            new TrailRequests.Coordinate(35.815, 127.15),
            new TrailRequests.Coordinate(35.82, 127.16),
            List.of(),
            TrailRequests.TravelMode.WALK
        )))
            .isInstanceOf(GoogleProviderException.class)
            .hasMessage("GOOGLE_ROUTES_NOT_CONFIGURED");
    }

    @Test
    void transitRequestPreservesModeAndIntermediateOrder() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleRoutesClient client = new GoogleRoutesClient(
            builder,
            new GoogleRoutesProperties(
                "routes-key",
                URI.create("https://example.test")
            ),
            new ObjectMapper()
        );
        server.expect(requestTo(
                "https://example.test/directions/v2:computeRoutes"
            ))
            .andExpect(content().string(containsString("\"travelMode\":\"TRANSIT\"")))
            .andExpect(content().string(matchesPattern(
                "(?s).*\"latitude\":35\\.82.*\"latitude\":35\\.83.*"
            )))
            .andRespond(withSuccess(
                "{\"routes\":[]}",
                MediaType.APPLICATION_JSON
            ));

        TrailResult result = client.compute(new TrailRequests.Compute(
            new TrailRequests.Coordinate(35.81, 127.15),
            new TrailRequests.Coordinate(35.84, 127.18),
            List.of(
                new TrailRequests.Coordinate(35.82, 127.16),
                new TrailRequests.Coordinate(35.83, 127.17)
            ),
            TrailRequests.TravelMode.TRANSIT
        ));

        assertThat(result.duration()).isNull();
        assertThat(result.distance_meters()).isNull();
        assertThat(result.encoded_polyline()).isNull();
        server.verify();
    }

    @Test
    void providerBadRequestIsStructuredAsNonRetryableBadGatewayInput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleRoutesClient client = new GoogleRoutesClient(
            builder,
            new GoogleRoutesProperties(
                "routes-key",
                URI.create("https://example.test")
            ),
            new ObjectMapper()
        );
        server.expect(requestTo(
                "https://example.test/directions/v2:computeRoutes"
            ))
            .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.compute(request()))
            .isInstanceOf(GoogleProviderException.class)
            .satisfies(error -> {
                GoogleProviderException provider =
                    (GoogleProviderException) error;
                assertThat(provider.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(provider.retryable()).isFalse();
            });
        server.verify();
    }

    @Test
    void providerServerErrorIsRetryableBadGateway() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleRoutesClient client = new GoogleRoutesClient(
            builder,
            new GoogleRoutesProperties(
                "routes-key",
                URI.create("https://example.test")
            ),
            new ObjectMapper()
        );
        server.expect(requestTo(
                "https://example.test/directions/v2:computeRoutes"
            ))
            .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.compute(request()))
            .isInstanceOf(GoogleProviderException.class)
            .satisfies(error -> {
                GoogleProviderException provider =
                    (GoogleProviderException) error;
                assertThat(provider.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(provider.retryable()).isFalse();
            });
        server.verify();
    }

    @Test
    void routeTimeoutIsRetryableBadGateway() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GoogleRoutesClient client = new GoogleRoutesClient(
            builder,
            new GoogleRoutesProperties(
                "routes-key",
                URI.create("https://example.test")
            ),
            new ObjectMapper()
        );
        server.expect(requestTo(
                "https://example.test/directions/v2:computeRoutes"
            ))
            .andRespond(request -> {
                throw new ResourceAccessException(
                    "timeout",
                    new SocketTimeoutException("timeout")
                );
            });

        assertThatThrownBy(() -> client.compute(request()))
            .isInstanceOf(GoogleProviderException.class)
            .satisfies(error -> {
                GoogleProviderException provider =
                    (GoogleProviderException) error;
                assertThat(provider.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(provider.retryable()).isTrue();
            });
        server.verify();
    }

    private static TrailRequests.Compute request() {
        return new TrailRequests.Compute(
            new TrailRequests.Coordinate(35.815, 127.15),
            new TrailRequests.Coordinate(35.82, 127.16),
            List.of(),
            TrailRequests.TravelMode.WALK
        );
    }
}
