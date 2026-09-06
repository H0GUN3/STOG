package com.stog.backend.place.search;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PlaceSearchRepository {
    private static final String SEARCH_SQL = """
        WITH eligible_places AS (
            SELECT
                p.id,
                p.name,
                p.category,
                p.lat,
                p.lng,
                p.normalized_name,
                p.compact_name,
                p.catalog_status,
                details.address,
                source_record.id AS source_record_id,
                source_record.external_id,
                catalog_source.provider_type AS source_type,
                ARRAY(
                    SELECT source_image.source_url
                    FROM place_source_images source_image
                    JOIN place_source_records image_record
                        ON image_record.id = source_image.place_source_record_id
                    JOIN catalog_sources image_source
                        ON image_source.id = image_record.catalog_source_id
                    JOIN license_snapshots image_license
                        ON image_license.id = source_image.license_snapshot_id
                    WHERE image_record.place_id = p.id
                      AND image_record.active
                      AND image_source.active
                      AND image_license.catalog_source_id = image_record.catalog_source_id
                      AND image_license.allows_public_discovery
                      AND image_license.valid_from <= CURRENT_DATE
                      AND (
                          image_license.valid_until IS NULL
                          OR image_license.valid_until >= CURRENT_DATE
                      )
                      AND image_license.reusable_fields ?? 'image'
                      AND source_image.reusable
                      AND source_image.source_url ~* '^https://[^[:space:]]+$'
                    ORDER BY source_image.id
                    LIMIT 2
                ) AS photo_urls
            FROM places p
            LEFT JOIN place_details details ON details.place_id = p.id
            JOIN LATERAL (
                SELECT
                    place_record.id,
                    place_record.catalog_source_id,
                    place_record.external_id
                FROM place_source_records place_record
                JOIN license_snapshots license
                    ON license.id = place_record.license_snapshot_id
                JOIN catalog_sources source
                    ON source.id = place_record.catalog_source_id
                WHERE place_record.place_id = p.id
                  AND place_record.active
                  AND source.active
                  AND license.allows_public_discovery
                  AND license.valid_from <= CURRENT_DATE
                  AND (
                      license.valid_until IS NULL
                      OR license.valid_until >= CURRENT_DATE
                  )
                  AND jsonb_array_length(license.reusable_fields) > 0
                ORDER BY place_record.id
                LIMIT 1
            ) source_record ON TRUE
            JOIN catalog_sources catalog_source
                ON catalog_source.id = source_record.catalog_source_id
            WHERE p.catalog_status = 'public'
        ), candidates AS (
            SELECT
                p.id,
                p.name,
                p.category,
                p.lat,
                p.lng,
                p.address,
                p.catalog_status,
                p.source_record_id,
                p.external_id,
                p.source_type,
                p.photo_urls,
                CASE
                    WHEN p.normalized_name = :normalizedName THEN 0
                    WHEN p.compact_name = :compactName THEN 1
                    WHEN COALESCE(BOOL_OR(
                        alias.normalized_name = :normalizedName
                        OR alias.compact_name = :compactName
                    ), FALSE) THEN 2
                    ELSE 3
                END AS match_rank,
                GREATEST(
                    similarity(p.normalized_name, :normalizedName),
                    similarity(p.compact_name, :compactName),
                    COALESCE(MAX(GREATEST(
                        similarity(alias.normalized_name, :normalizedName),
                        similarity(alias.compact_name, :compactName)
                    )), 0)
                ) AS fuzzy_score
            FROM eligible_places p
            LEFT JOIN place_aliases alias ON alias.place_id = p.id
            GROUP BY
                p.id,
                p.name,
                p.category,
                p.lat,
                p.lng,
                p.address,
                p.catalog_status,
                p.source_record_id,
                p.external_id,
                p.source_type,
                p.photo_urls,
                p.normalized_name,
                p.compact_name
        )
        SELECT
            id,
            name,
            category,
            lat,
            lng,
            address,
            catalog_status,
            source_record_id,
            external_id,
            source_type,
            photo_urls
        FROM candidates
        WHERE match_rank < 3 OR fuzzy_score >= :fuzzyThreshold
        ORDER BY
            match_rank ASC,
            CASE WHEN match_rank = 3 THEN fuzzy_score END DESC,
            id ASC
        LIMIT :limit
        """;

    private static final String NEARBY_SQL = """
        WITH eligible_places AS (
            SELECT
                p.id,
                p.name,
                p.category,
                p.lat,
                p.lng,
                CASE
                    WHEN source_record.reusable_fields @> '["address"]'::jsonb
                        THEN details.address
                    ELSE NULL
                END AS address,
                p.catalog_status,
                source_record.id AS source_record_id,
                source_record.external_id,
                catalog_source.provider_type AS source_type,
                ARRAY(
                    SELECT source_image.source_url
                    FROM place_source_images source_image
                    JOIN place_source_records image_record
                        ON image_record.id = source_image.place_source_record_id
                    JOIN catalog_sources image_source
                        ON image_source.id = image_record.catalog_source_id
                    JOIN license_snapshots image_license
                        ON image_license.id = source_image.license_snapshot_id
                    WHERE image_record.place_id = p.id
                      AND image_record.active
                      AND image_source.active
                      AND image_license.catalog_source_id = image_record.catalog_source_id
                      AND image_license.allows_public_discovery
                      AND image_license.valid_from <= CURRENT_DATE
                      AND (
                          image_license.valid_until IS NULL
                          OR image_license.valid_until >= CURRENT_DATE
                      )
                      AND image_license.reusable_fields ?? 'image'
                      AND source_image.reusable
                      AND source_image.source_url ~* '^https://[^[:space:]]+$'
                    ORDER BY source_image.id
                    LIMIT 2
                ) AS photo_urls
            FROM places p
            LEFT JOIN place_details details ON details.place_id = p.id
            JOIN LATERAL (
                SELECT
                    place_record.id,
                    place_record.catalog_source_id,
                    place_record.external_id,
                    license.reusable_fields
                FROM place_source_records place_record
                JOIN license_snapshots license
                    ON license.id = place_record.license_snapshot_id
                JOIN catalog_sources source
                    ON source.id = place_record.catalog_source_id
                WHERE place_record.place_id = p.id
                  AND place_record.active
                  AND source.active
                  AND license.allows_public_discovery
                  AND license.valid_from <= CURRENT_DATE
                  AND (
                      license.valid_until IS NULL
                      OR license.valid_until >= CURRENT_DATE
                  )
                  AND license.reusable_fields @> '["name"]'::jsonb
                ORDER BY place_record.id
                LIMIT 1
            ) source_record ON TRUE
            JOIN catalog_sources catalog_source
                ON catalog_source.id = source_record.catalog_source_id
            WHERE p.catalog_status = 'public'
              AND p.public_cell_eligible
              AND LOWER(p.category) IN (
                  'tourist_place',
                  'tourist_attraction',
                  'attraction',
                  'museum',
                  'park',
                  'art_gallery',
                  'national_park',
                  'state_park',
                  'historical_landmark',
                  'zoo',
                  'aquarium',
                  'amusement_park',
                  'nature',
                  'history',
                  'culture',
                  'experience',
                  'tour_culture',
                  'tour_history',
                  'tour_nature',
                  'tour_experience'
              )
        ), distances AS (
            SELECT
                p.*,
                6371000.0 * 2.0 * ASIN(SQRT(
                    POWER(SIN(RADIANS(p.lat - :latitude) / 2.0), 2.0)
                    + COS(RADIANS(:latitude)) * COS(RADIANS(p.lat))
                    * POWER(SIN(RADIANS(p.lng - :longitude) / 2.0), 2.0)
                )) AS distance_meters
            FROM eligible_places p
        )
        SELECT
            id,
            name,
            category,
            lat,
            lng,
            address,
            catalog_status,
            source_record_id,
            external_id,
            source_type,
            photo_urls
        FROM distances
        WHERE distance_meters <= :radiusMeters
        ORDER BY distance_meters ASC, id ASC
        LIMIT :limit
        """;

    private final JdbcClient jdbc;

    public PlaceSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LocalResult> search(
        PlaceSearchNormalizer.Normalized query,
        int limit,
        double fuzzyThreshold
    ) {
        return jdbc.sql(SEARCH_SQL)
            .params(Map.of(
                "normalizedName", query.normalized_name(),
                "compactName", query.compact_name(),
                "fuzzyThreshold", fuzzyThreshold,
                "limit", limit
            ))
            .query((row, rowNumber) -> new LocalResult(
                row.getLong("id"),
                row.getString("external_id"),
                row.getString("name"),
                row.getString("address"),
                row.getObject("lat", Double.class),
                row.getObject("lng", Double.class),
                row.getString("category"),
                row.getString("source_type"),
                row.getLong("source_record_id"),
                row.getString("catalog_status"),
                LocalResult.photoUrls(row)
            ))
            .list();
    }

    public List<LocalResult> nearby(
        double latitude,
        double longitude,
        double radiusMeters,
        int limit
    ) {
        return jdbc.sql(NEARBY_SQL)
            .params(Map.of(
                "latitude", latitude,
                "longitude", longitude,
                "radiusMeters", radiusMeters,
                "limit", limit
            ))
            .query((row, rowNumber) -> new LocalResult(
                row.getLong("id"),
                row.getString("external_id"),
                row.getString("name"),
                row.getString("address"),
                row.getObject("lat", Double.class),
                row.getObject("lng", Double.class),
                row.getString("category"),
                row.getString("source_type"),
                row.getLong("source_record_id"),
                row.getString("catalog_status"),
                LocalResult.photoUrls(row)
            ))
            .list();
    }

    public record LocalResult(
        long placeId,
        String externalId,
        String name,
        String address,
        Double latitude,
        Double longitude,
        String category,
        String sourceType,
        long sourceId,
        String catalogStatus,
        List<String> photoUrls
    ) {
        public PlaceSearchResult toSearchResult() {
            return new PlaceSearchResult(
                "canonical",
                externalId,
                name,
                address,
                latitude,
                longitude,
                normalizedTypes(category),
                List.of(),
                null,
                null,
                null,
                List.of(),
                photoUrls,
                null,
                null,
                null,
                null,
                null,
                PlaceSearchProvenance.canonical(
                    placeId,
                    sourceType,
                    sourceId,
                    catalogStatus
                )
            );
        }

        private static List<String> photoUrls(ResultSet row) throws SQLException {
            Array value = row.getArray("photo_urls");
            if (value == null || !(value.getArray() instanceof Object[] values)) {
                return List.of();
            }
            return Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(url -> !url.isBlank())
                .toList();
        }

        private static List<String> normalizedTypes(String category) {
            if (category == null || category.isBlank()) {
                return List.of();
            }
            return List.of(switch (category.toUpperCase(java.util.Locale.ROOT)) {
                case "ATTRACTION", "TOURIST_ATTRACTION", "TOURIST_PLACE" -> "tourist_attraction";
                case "FOOD", "RESTAURANT" -> "restaurant";
                case "CAFE", "COFFEE_SHOP" -> "cafe";
                case "ACCOMMODATION", "LODGING", "HOTEL" -> "lodging";
                case "SHOPPING", "STORE" -> "shopping";
                case "TRANSPORT" -> "transit_station";
                case "SERVICE" -> "service";
                default -> "place";
            });
        }
    }
}
