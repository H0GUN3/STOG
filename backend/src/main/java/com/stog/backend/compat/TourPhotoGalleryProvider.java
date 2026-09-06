package com.stog.backend.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Imports reusable PhotoGallery images for places already in the canonical
 * catalog. Search results are accepted only when their title or location
 * contains the canonical place name.
 */
@Component
@Primary
@Profile("catalog-refresh")
public class TourPhotoGalleryProvider implements CatalogRefreshProvider {
    private static final String SOURCE = "TOUR_API";
    private static final String LICENSE = "APPROVED_PUBLIC_REUSE";
    private static final Logger LOGGER =
        LoggerFactory.getLogger(TourPhotoGalleryProvider.class);

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final TourPhotoGalleryProperties properties;
    private final JdbcClient jdbc;

    public TourPhotoGalleryProvider(
        @Qualifier("socialRestClientBuilder") RestClient.Builder builder,
        TourPhotoGalleryProperties properties,
        JdbcClient jdbc
    ) {
        this.client = builder.requestFactory(requestFactory(properties)).build();
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public PublicCatalogSnapshot fetch(String runId) {
        if (properties.url() == null
            || !properties.url().isAbsolute()
            || properties.serviceKey() == null
            || properties.serviceKey().isBlank()) {
            throw new CatalogRefreshProviderException("provider_not_configured");
        }

        List<PlaceTarget> targets = places();
        List<CloudPlaceSourceRow> rows = new ArrayList<>();
        for (PlaceTarget place : targets) {
            List<CloudPlaceSourceImage> images = search(place.name(), runId);
            if (!images.isEmpty()) {
                rows.add(imageRow(place, images));
            }
        }
        LOGGER.info(
            "TourPhotoGallery refresh scanned {} public places and matched {} image rows",
            targets.size(),
            rows.size()
        );
        return new PublicCatalogSnapshot(SOURCE, LICENSE, true, rows);
    }

    static List<CloudPlaceSourceImage> parseImages(String response, String placeName) {
        if (response == null || response.isBlank()) {
            throw new CatalogRefreshProviderException("malformed_response");
        }
        try {
            JsonNode root = new ObjectMapper().readTree(response);
            JsonNode header = root.path("response").path("header");
            String resultCode = text(header, "resultCode");
            if (resultCode == null) {
                throw new CatalogRefreshProviderException("malformed_response");
            }
            if (!"0000".equals(resultCode)) {
                throw new CatalogRefreshProviderException("provider_failure_" + resultCode);
            }

            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || items.isNull()) {
                return List.of();
            }
            List<CloudPlaceSourceImage> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    addImage(result, seen, item, placeName);
                }
            } else if (items.isObject()) {
                addImage(result, seen, items, placeName);
            }
            return List.copyOf(result);
        } catch (CatalogRefreshProviderException error) {
            throw error;
        } catch (RuntimeException | java.io.IOException error) {
            throw new CatalogRefreshProviderException("malformed_response", error);
        }
    }

    private List<CloudPlaceSourceImage> search(String placeName, String runId) {
        URI endpoint = UriComponentsBuilder.fromUri(properties.url())
            .queryParam("MobileOS", "ETC")
            .queryParam("MobileApp", "STOG")
            .queryParam("_type", "json")
            .queryParam("numOfRows", properties.pageSize())
            .queryParam("pageNo", 1)
            .queryParam("keyword", UriUtils.encodeQueryParam(placeName, StandardCharsets.UTF_8))
            .queryParam("serviceKey", normalizeServiceKey(properties.serviceKey()))
            .build(true)
            .toUri();
        try {
            String response = client.get()
                .uri(endpoint)
                .header("X-STOG-Catalog-Run", runId)
                .retrieve()
                .body(String.class);
            return parseImages(response, placeName);
        } catch (RestClientResponseException error) {
            String code = error.getStatusCode().value() == 429
                ? "quota_exhausted"
                : "provider_failure_" + error.getStatusCode().value();
            throw new CatalogRefreshProviderException(code, error);
        } catch (RestClientException error) {
            throw new CatalogRefreshProviderException("provider_timeout", error);
        }
    }

    private List<PlaceTarget> places() {
        try {
            return jdbc.sql(
                    """
                    SELECT DISTINCT ON (place.id)
                           place.external_id, place.name, place.category,
                           place.lat, place.lng
                    FROM places place
                    JOIN place_source_records record
                      ON record.place_id = place.id
                    WHERE place.source = 'public_data'
                      AND place.catalog_status = 'public'
                      AND place.public_cell_eligible
                      AND record.active
                    ORDER BY place.id, record.id
                    LIMIT :maxPlaces
                    """
                )
                .param("maxPlaces", properties.maxPlaces())
                .query((row, rowNumber) -> new PlaceTarget(
                    row.getString("external_id"),
                    row.getString("name"),
                    row.getString("category"),
                    row.getObject("lat", Double.class),
                    row.getObject("lng", Double.class)
                ))
                .list();
        } catch (CatalogRefreshProviderException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CatalogRefreshProviderException("catalog_query_failed", error);
        }
    }

    private static CloudPlaceSourceRow imageRow(
        PlaceTarget place,
        List<CloudPlaceSourceImage> images
    ) {
        return new CloudPlaceSourceRow(
            place.externalId(),
            place.category(),
            place.name(),
            null,
            place.latitude(),
            place.longitude(),
            null,
            SOURCE,
            place.externalId(),
            "ACTIVE",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            CloudPlaceJson.sha256(images),
            LICENSE,
            List.of("image"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            images
        );
    }

    private static void addImage(
        List<CloudPlaceSourceImage> result,
        Set<String> seen,
        JsonNode item,
        String placeName
    ) {
        if (!matches(
            placeName,
            text(item, "galTitle"),
            text(item, "galPhotographyLocation"),
            text(item, "galSearchKeyword")
        )) {
            return;
        }
        String galleryId = text(item, "galContentId");
        String imageUrl = firstText(
            item,
            "galWebImageUrl",
            "galImageUrl",
            "galSearchImageUrl"
        );
        if (galleryId == null || imageUrl == null || !isHttpsUrl(imageUrl)) {
            return;
        }
        String sourceDigest = CloudPlaceJson.sha256(imageUrl);
        String sourceImageId = galleryId + ":" + sourceDigest;
        if (seen.add(sourceImageId)) {
            result.add(new CloudPlaceSourceImage(
                sourceImageId,
                imageUrl,
                sourceDigest,
                true
            ));
        }
    }

    private static boolean matches(String placeName, String... fields) {
        String needle = compact(placeName);
        return !needle.isBlank()
            && java.util.Arrays.stream(fields)
                .map(TourPhotoGalleryProvider::compact)
                .anyMatch(value -> value.contains(needle));
    }

    private static String compact(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = text(item, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.path(field).isTextual()) {
            return null;
        }
        String value = node.path(field).textValue().trim();
        return value.isBlank() ? null : value;
    }

    private static boolean isHttpsUrl(String value) {
        try {
            return "https".equalsIgnoreCase(URI.create(value).getScheme());
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static String normalizeServiceKey(String value) {
        String plusSafe = value.replace("+", "%2B");
        String decoded = URLDecoder.decode(plusSafe, StandardCharsets.UTF_8);
        return UriUtils.encodeQueryParam(decoded, StandardCharsets.UTF_8)
            .replace("+", "%2B");
    }

    private static SimpleClientHttpRequestFactory requestFactory(
        TourPhotoGalleryProperties properties
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout(properties.connectTimeout()));
        requestFactory.setReadTimeout(timeout(properties.readTimeout()));
        return requestFactory;
    }

    private static Duration timeout(Duration configured) {
        return configured == null ? Duration.ofSeconds(3) : configured;
    }

    private record PlaceTarget(
        String externalId,
        String name,
        String category,
        Double latitude,
        Double longitude
    ) {
    }
}
