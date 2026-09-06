package com.stog.backend.place;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stog.backend.google.GoogleProviderException;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GooglePlacesClient {
    private static final String PROVIDER = "google";
    private static final String SEARCH_FIELD_MASK = String.join(
        ",",
        "places.id",
        "places.displayName",
        "places.formattedAddress",
        "places.location",
        "places.types",
        "places.rating",
        "places.userRatingCount",
        "places.businessStatus",
        "places.currentOpeningHours.openNow",
        "places.currentOpeningHours.nextCloseTime",
        "places.regularOpeningHours.weekdayDescriptions",
        "places.nationalPhoneNumber",
        "places.websiteUri",
        "places.googleMapsUri",
        "places.photos.name"
    );
    private static final String DETAILS_FIELD_MASK = SEARCH_FIELD_MASK
        .replace("places.", "");
    private static final String PHOTO_NAME_PATTERN =
        "places/[A-Za-z0-9_-]+/photos/[A-Za-z0-9_-]+";
    private static final int PHOTO_MAX_WIDTH_PX = 800;

    private final RestClient client;
    private final GooglePlacesProperties properties;
    private final ObjectMapper objectMapper;

    public GooglePlacesClient(
        @Qualifier("googleRestClientBuilder") RestClient.Builder builder,
        GooglePlacesProperties properties,
        ObjectMapper objectMapper
    ) {
        this.client = builder.baseUrl(properties.baseUrl().toString()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<PlaceCandidate> textSearch(PlaceRequests.Search request) {
        validateBias(request);
        LocationBias bias = request.latitude() == null
            ? null
            : new LocationBias(new Circle(
                new Center(request.latitude(), request.longitude()),
                request.radius_meters()
            ));
        JsonNode response = post(
            "/places:searchText",
            new TextSearchBody(
                request.query(),
                properties.defaultLanguageCode(),
                properties.defaultRegionCode(),
                request.max_result_count(),
                bias
            ),
            SEARCH_FIELD_MASK,
            "GOOGLE_PLACES_SEARCH_FAILED"
        );
        return places(response);
    }

    public PlaceCandidate details(String placeId) {
        if (placeId == null || placeId.isBlank() || placeId.contains("/")) {
            throw new IllegalArgumentException("placeId is invalid");
        }
        JsonNode response = get(
            "/places/" + placeId,
            DETAILS_FIELD_MASK,
            "GOOGLE_PLACES_DETAILS_FAILED"
        );
        return mapPlace(response);
    }

    public PhotoResponse photo(String photoName) {
        if (photoName == null || !photoName.matches(PHOTO_NAME_PATTERN)) {
            throw new IllegalArgumentException("photo name is invalid");
        }
        requireApiKey("GOOGLE_PLACES_PHOTO_FAILED");
        try {
            ResponseEntity<byte[]> response = client.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/" + photoName + "/media")
                    .queryParam("maxWidthPx", PHOTO_MAX_WIDTH_PX)
                    .build())
                .header("X-Goog-Api-Key", properties.apiKey())
                .retrieve()
                .toEntity(byte[].class);
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length == 0) {
                throw new RestClientException("empty photo response");
            }
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null
                && contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                JsonNode photoResponse = objectMapper.readTree(bytes);
                String photoUri = text(photoResponse, "photoUri");
                if (photoUri == null) {
                    throw new RestClientException("photo URI is missing");
                }
                response = client.get()
                    .uri(URI.create(photoUri))
                    .retrieve()
                    .toEntity(byte[].class);
                bytes = response.getBody();
                if (bytes == null || bytes.length == 0) {
                    throw new RestClientException("empty photo URI response");
                }
                contentType = response.getHeaders().getContentType();
            }
            return new PhotoResponse(
                bytes,
                contentType == null ? MediaType.IMAGE_JPEG : contentType
            );
        } catch (RestClientResponseException error) {
            throw providerError(error, "GOOGLE_PLACES_PHOTO_FAILED");
        } catch (RestClientException error) {
            throw new GoogleProviderException(
                HttpStatus.BAD_GATEWAY,
                "GOOGLE_PLACES_PHOTO_FAILED",
                true
            );
        }
    }

    public List<PlaceCandidate> nearbySearch(PlaceRequests.Nearby request) {
        JsonNode response = post(
            "/places:searchNearby",
            new NearbySearchBody(
                request.included_types(),
                properties.defaultLanguageCode(),
                properties.defaultRegionCode(),
                request.max_result_count(),
                new LocationRestriction(new Circle(
                    new Center(
                        request.center().latitude(),
                        request.center().longitude()
                    ),
                    request.radius_meters()
                ))
            ),
            SEARCH_FIELD_MASK,
            "GOOGLE_PLACES_NEARBY_SEARCH_FAILED"
        );
        return places(response);
    }

    private void validateBias(PlaceRequests.Search request) {
        boolean hasAny = request.latitude() != null
            || request.longitude() != null
            || request.radius_meters() != null;
        boolean hasAll = request.latitude() != null
            && request.longitude() != null
            && request.radius_meters() != null;
        if (hasAny && !hasAll) {
            throw new IllegalArgumentException(
                "latitude, longitude, and radius_meters must be provided together"
            );
        }
    }

    private JsonNode post(
        String path,
        Object body,
        String fieldMask,
        String errorCode
    ) {
        requireApiKey(errorCode);
        try {
            JsonNode response = client.post()
                .uri(path)
                .header("X-Goog-Api-Key", properties.apiKey())
                .header("X-Goog-FieldMask", fieldMask)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            return response == null ? objectMapper.createObjectNode() : response;
        } catch (RestClientResponseException error) {
            throw providerError(error, errorCode);
        } catch (RestClientException error) {
            throw new GoogleProviderException(
                HttpStatus.BAD_GATEWAY,
                errorCode,
                true
            );
        }
    }

    private JsonNode get(String path, String fieldMask, String errorCode) {
        requireApiKey(errorCode);
        try {
            JsonNode response = client.get()
                .uri(path)
                .header("X-Goog-Api-Key", properties.apiKey())
                .header("X-Goog-FieldMask", fieldMask)
                .retrieve()
                .body(JsonNode.class);
            return response == null ? objectMapper.createObjectNode() : response;
        } catch (RestClientResponseException error) {
            throw providerError(error, errorCode);
        } catch (RestClientException error) {
            throw new GoogleProviderException(
                HttpStatus.BAD_GATEWAY,
                errorCode,
                true
            );
        }
    }

    private void requireApiKey(String errorCode) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new GoogleProviderException(
                HttpStatus.SERVICE_UNAVAILABLE,
                errorCode + "_NOT_CONFIGURED",
                false
            );
        }
    }

    private GoogleProviderException providerError(
        RestClientResponseException error,
        String errorCode
    ) {
        int status = error.getStatusCode().value();
        boolean retryable = status == 429 || status >= 500;
        HttpStatus mapped = status == 401 || status == 403
            ? HttpStatus.BAD_GATEWAY
            : retryable
            ? HttpStatus.BAD_GATEWAY
            : HttpStatus.BAD_REQUEST;
        return new GoogleProviderException(mapped, errorCode, retryable);
    }

    private List<PlaceCandidate> places(JsonNode response) {
        List<PlaceCandidate> result = new ArrayList<>();
        JsonNode values = response.path("places");
        if (!values.isArray()) {
            return result;
        }
        for (JsonNode value : values) {
            result.add(mapPlace(value));
        }
        return result;
    }

    private PlaceCandidate mapPlace(JsonNode value) {
        JsonNode displayName = value.path("displayName");
        JsonNode location = value.path("location");
        JsonNode currentOpeningHours = value.path("currentOpeningHours");
        return new PlaceCandidate(
            PROVIDER,
            text(value, "id"),
            text(displayName, "text"),
            text(value, "formattedAddress"),
            number(location, "latitude"),
            number(location, "longitude"),
            strings(value.path("types")),
            strings(value.path("regularOpeningHours").path("weekdayDescriptions")),
            text(value, "nationalPhoneNumber"),
            text(value, "websiteUri"),
            text(value, "googleMapsUri"),
            strings(value.path("photos"), "name"),
            number(value, "rating"),
            integer(value, "userRatingCount"),
            text(value, "businessStatus"),
            bool(currentOpeningHours, "openNow"),
            text(currentOpeningHours, "nextCloseTime")
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode value : node) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode value : node) {
                String text = text(value, field);
                if (text != null) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record TextSearchBody(
        String textQuery,
        String languageCode,
        String regionCode,
        Integer maxResultCount,
        LocationBias locationBias
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record NearbySearchBody(
        List<String> includedTypes,
        String languageCode,
        String regionCode,
        Integer maxResultCount,
        LocationRestriction locationRestriction
    ) {
    }

    private record LocationBias(Circle circle) {
    }

    private record LocationRestriction(Circle circle) {
    }

    private record Circle(Center center, double radius) {
    }

    private record Center(double latitude, double longitude) {
    }

    public record PhotoResponse(byte[] bytes, MediaType contentType) {
    }
}
