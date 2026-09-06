package com.stog.backend.plan;

import com.stog.backend.place.search.PlaceSearchNormalizer;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class PlaceRepository {
    private final JdbcClient jdbc;
    private final PlaceSearchNormalizer normalizer;

    public PlaceRepository(JdbcClient jdbc, PlaceSearchNormalizer normalizer) {
        this.jdbc = jdbc;
        this.normalizer = normalizer;
    }

    public CanonicalReference requireCanonical(
        long placeId,
        long sourceRecordId,
        String externalId
    ) {
        return jdbc.sql("""
                SELECT
                    place.id,
                    place.name,
                    place.category,
                    place.lat,
                    place.lng,
                    place.cell_id,
                    place.source,
                    source_record.external_id
                FROM places place
                JOIN place_source_records source_record
                    ON source_record.id = :sourceRecordId
                   AND source_record.place_id = place.id
                JOIN catalog_sources catalog_source
                    ON catalog_source.id = source_record.catalog_source_id
                JOIN license_snapshots license_snapshot
                    ON license_snapshot.id = source_record.license_snapshot_id
                WHERE place.id = :placeId
                  AND source_record.external_id = :externalId
                  AND place.catalog_status = 'public'
                  AND source_record.active
                  AND catalog_source.active
                  AND license_snapshot.allows_public_discovery
                  AND license_snapshot.valid_from <= CURRENT_DATE
                  AND (
                      license_snapshot.valid_until IS NULL
                      OR license_snapshot.valid_until >= CURRENT_DATE
                  )
                  AND jsonb_array_length(license_snapshot.reusable_fields) > 0
                """)
            .param("placeId", placeId)
            .param("sourceRecordId", sourceRecordId)
            .param("externalId", externalId)
            .query((row, rowNumber) -> new CanonicalReference(
                row.getLong("id"),
                row.getString("name"),
                row.getString("category"),
                row.getObject("lat", Double.class),
                row.getObject("lng", Double.class),
                nullableLong(row.getObject("cell_id")),
                row.getString("source"),
                row.getString("external_id"),
                sourceRecordId
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Canonical place reference is invalid"
            ));
    }

    public Upserted upsertPrivateReference(BasketRequests.AddPlace request) {
        PlaceSearchNormalizer.Normalized normalized = normalizer.normalize(request.name());
        return jdbc.sql(
                """
                INSERT INTO places (
                    name,
                    category,
                    lat,
                    lng,
                    cell_id,
                    source,
                    external_id,
                    normalized_name,
                    compact_name
                )
                VALUES (
                    :name,
                    :category,
                    :latitude,
                    :longitude,
                    NULL,
                    :source,
                    :externalId,
                    :normalizedName,
                    :compactName
                )
                ON CONFLICT (source, external_id) DO UPDATE
                SET name = EXCLUDED.name,
                    category = EXCLUDED.category,
                    lat = EXCLUDED.lat,
                    lng = EXCLUDED.lng,
                    cell_id = NULL,
                    normalized_name = EXCLUDED.normalized_name,
                    compact_name = EXCLUDED.compact_name
                WHERE places.catalog_status = 'private_reference'
                RETURNING id, cell_id
                """
            )
            .params(Map.of(
                "name", request.name(),
                "category", request.category(),
                "latitude", request.latitude(),
                "longitude", request.longitude(),
                "source", request.provider(),
                "externalId", request.external_id(),
                "normalizedName", normalized.normalized_name(),
                "compactName", normalized.compact_name()
            ))
            .query((row, rowNumber) -> new Upserted(
                row.getLong("id"),
                nullableLong(row.getObject("cell_id"))
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Provider place conflicts with a public catalog place"
            ));
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public record CanonicalReference(
        long id,
        String name,
        String category,
        Double latitude,
        Double longitude,
        Long cellId,
        String source,
        String externalId,
        long sourceRecordId
    ) {
        public Upserted place() {
            return new Upserted(id, cellId);
        }
    }

    public record Upserted(long id, Long cellId) {
    }
}
