package com.stog.backend.trail;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stog.backend.google.GoogleProviderException;
import com.stog.backend.trail.TrailRequests.Compute;
import com.stog.backend.trail.TrailRequests.Coordinate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GoogleRoutesClient implements TrailCalculator {
    private static final String FIELD_MASK = String.join(
        ",",
        "routes.duration",
        "routes.distanceMeters",
        "routes.polyline.encodedPolyline"
    );

    private final RestClient client;
    private final GoogleRoutesProperties properties;
    private final ObjectMapper objectMapper;

    public GoogleRoutesClient(
        @Qualifier("googleRestClientBuilder") RestClient.Builder builder,
        GoogleRoutesProperties properties,
        ObjectMapper objectMapper
    ) {
        this.client = builder.baseUrl(properties.baseUrl().toString()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public TrailResult compute(Compute request) {
        requireApiKey();
        try {
            JsonNode response = client.post()
                .uri("/directions/v2:computeRoutes")
                .header("X-Goog-Api-Key", properties.apiKey())
                .header("X-Goog-FieldMask", FIELD_MASK)
                .body(body(request))
                .retrieve()
                .body(JsonNode.class);
            return mapResult(response == null
                ? objectMapper.createObjectNode()
                : response);
        } catch (RestClientResponseException error) {
            throw providerError(error);
        } catch (RestClientException error) {
            throw new GoogleProviderException(
                HttpStatus.BAD_GATEWAY,
                "GOOGLE_ROUTES_FAILED",
                true
            );
        }
    }

    private RouteBody body(Compute request) {
        List<Waypoint> intermediates = new ArrayList<>();
        for (Coordinate coordinate : request.intermediates()) {
            intermediates.add(waypoint(coordinate));
        }
        return new RouteBody(
            waypoint(request.origin()),
            waypoint(request.destination()),
            intermediates,
            request.travel_mode().name()
        );
    }

    private static Waypoint waypoint(Coordinate coordinate) {
        return new Waypoint(new Location(new LatLng(
            coordinate.latitude(),
            coordinate.longitude()
        )));
    }

    private TrailResult mapResult(JsonNode response) {
        JsonNode first = response.path("routes").path(0);
        return new TrailResult(
            text(first, "duration"),
            first.has("distanceMeters")
                ? first.get("distanceMeters").asLong()
                : null,
            text(first.path("polyline"), "encodedPolyline")
        );
    }

    private void requireApiKey() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new GoogleProviderException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "GOOGLE_ROUTES_NOT_CONFIGURED",
                false
            );
        }
    }

    private GoogleProviderException providerError(
        RestClientResponseException error
    ) {
        int status = error.getStatusCode().value();
        boolean retryable = status == 429 || status >= 500;
        boolean configurationFailure = status == 401 || status == 403;
        return new GoogleProviderException(
            configurationFailure || retryable
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.BAD_REQUEST,
            "GOOGLE_ROUTES_FAILED",
            retryable
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RouteBody(
        Waypoint origin,
        Waypoint destination,
        List<Waypoint> intermediates,
        String travelMode
    ) {
    }

    private record Waypoint(Location location) {
    }

    private record Location(LatLng latLng) {
    }

    private record LatLng(double latitude, double longitude) {
    }
}
