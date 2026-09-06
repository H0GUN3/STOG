package com.stog.backend.storage;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PhotoPlaceRepository {
    private final JdbcClient jdbc;

    public PhotoPlaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> findEligibleCandidates(
        double minLatitude,
        double maxLatitude,
        double minLongitude,
        double maxLongitude,
        boolean crossesAntimeridian
    ) {
        String longitudePredicate = crossesAntimeridian
            ? "(p.lng >= :minLongitude OR p.lng <= :maxLongitude)"
            : "p.lng BETWEEN :minLongitude AND :maxLongitude";
        return jdbc.sql("""
                SELECT p.id, p.name, p.lat, p.lng
                FROM places p
                WHERE p.lat IS NOT NULL
                  AND p.lng IS NOT NULL
                  AND p.catalog_status = 'public'
                  AND p.lat BETWEEN :minLatitude AND :maxLatitude
                  AND %s
                  AND EXISTS (
                      SELECT 1
                      FROM place_source_records source_record
                      JOIN catalog_sources source
                        ON source.id = source_record.catalog_source_id
                      JOIN license_snapshots license
                        ON license.id = source_record.license_snapshot_id
                       AND license.catalog_source_id = source_record.catalog_source_id
                      WHERE source_record.place_id = p.id
                        AND source_record.active
                        AND source.active
                        AND license.allows_public_discovery
                        AND license.valid_from <= CURRENT_DATE
                        AND (license.valid_until IS NULL OR license.valid_until >= CURRENT_DATE)
                        AND jsonb_exists(license.reusable_fields, 'name')
                  )
                ORDER BY p.id
                """.formatted(longitudePredicate))
            .params(Map.of(
                "minLatitude", minLatitude,
                "maxLatitude", maxLatitude,
                "minLongitude", minLongitude,
                "maxLongitude", maxLongitude
            ))
            .query((row, rowNumber) -> new Candidate(
                row.getLong("id"),
                row.getString("name"),
                row.getDouble("lat"),
                row.getDouble("lng")
            ))
            .list();
    }

    public record Candidate(long id, String name, double latitude, double longitude) {
    }
}
