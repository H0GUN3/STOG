package com.stog.backend.event;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {
    private static final String NEARBY_SQL = """
        WITH candidate_events AS (
            SELECT
                id,
                provider,
                external_id,
                title,
                venue_name,
                formatted_address,
                latitude,
                longitude,
                starts_on,
                ends_on,
                detail_uri,
                image_uri,
                6371000.0 * 2.0 * ASIN(SQRT(
                    POWER(SIN(RADIANS(latitude - :latitude) / 2.0), 2.0)
                    + COS(RADIANS(:latitude)) * COS(RADIANS(latitude))
                    * POWER(SIN(RADIANS(longitude - :longitude) / 2.0), 2.0)
                )) AS distance_meters
            FROM events
            WHERE status = 'active'
              AND starts_on <= :toDate
              AND ends_on >= :fromDate
        )
        SELECT
            provider,
            external_id,
            title,
            venue_name,
            formatted_address,
            latitude,
            longitude,
            starts_on,
            ends_on,
            detail_uri,
            image_uri
        FROM candidate_events
        WHERE distance_meters <= :radiusMeters
        ORDER BY distance_meters ASC, starts_on ASC, id ASC
        LIMIT :limit
        """;

    private static final String UPSERT_SQL = """
        INSERT INTO events (
            provider,
            external_id,
            title,
            venue_name,
            formatted_address,
            latitude,
            longitude,
            starts_on,
            ends_on,
            detail_uri,
            image_uri,
            source_updated_at,
            status,
            updated_at
        )
        VALUES (
            :provider,
            :externalId,
            :title,
            :venueName,
            :formattedAddress,
            :latitude,
            :longitude,
            :startsOn,
            :endsOn,
            :detailUri,
            :imageUri,
            :sourceUpdatedAt,
            'active',
            CURRENT_TIMESTAMP
        )
        ON CONFLICT (provider, external_id) DO UPDATE SET
            title = EXCLUDED.title,
            venue_name = EXCLUDED.venue_name,
            formatted_address = EXCLUDED.formatted_address,
            latitude = EXCLUDED.latitude,
            longitude = EXCLUDED.longitude,
            starts_on = EXCLUDED.starts_on,
            ends_on = EXCLUDED.ends_on,
            detail_uri = EXCLUDED.detail_uri,
            image_uri = EXCLUDED.image_uri,
            source_updated_at = EXCLUDED.source_updated_at,
            status = 'active',
            updated_at = CURRENT_TIMESTAMP
        """;

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EventRow> nearby(
        double latitude,
        double longitude,
        double radiusMeters,
        LocalDate fromDate,
        LocalDate toDate,
        int limit
    ) {
        return jdbc.sql(NEARBY_SQL)
            .params(Map.of(
                "latitude", latitude,
                "longitude", longitude,
                "radiusMeters", radiusMeters,
                "fromDate", fromDate,
                "toDate", toDate,
                "limit", limit
            ))
            .query((row, rowNumber) -> new EventRow(
                row.getString("provider"),
                row.getString("external_id"),
                row.getString("title"),
                row.getString("venue_name"),
                row.getString("formatted_address"),
                row.getObject("latitude", Double.class),
                row.getObject("longitude", Double.class),
                row.getObject("starts_on", LocalDate.class),
                row.getObject("ends_on", LocalDate.class),
                row.getString("detail_uri"),
                row.getString("image_uri")
            ))
            .list();
    }

    public void upsert(TourApiFestival festival) {
        jdbc.sql(UPSERT_SQL)
            .param("provider", festival.provider())
            .param("externalId", festival.externalId())
            .param("title", festival.title())
            .param("venueName", festival.venueName())
            .param("formattedAddress", festival.formattedAddress())
            .param("latitude", festival.latitude())
            .param("longitude", festival.longitude())
            .param("startsOn", festival.startsOn())
            .param("endsOn", festival.endsOn())
            .param("detailUri", festival.detailUri())
            .param("imageUri", festival.imageUri())
            .param("sourceUpdatedAt", festival.sourceUpdatedAt())
            .update();
    }

    public int retireExpired(LocalDate today) {
        return jdbc.sql("""
                UPDATE events
                SET status = 'retired',
                    updated_at = CURRENT_TIMESTAMP
                WHERE status = 'active'
                  AND ends_on < :today
                """)
            .param("today", today)
            .update();
    }

    public record EventRow(
        String provider,
        String externalId,
        String title,
        String venueName,
        String formattedAddress,
        Double latitude,
        Double longitude,
        LocalDate startsOn,
        LocalDate endsOn,
        String detailUri,
        String imageUri
    ) {
        EventSearchResult toSearchResult() {
            return new EventSearchResult(
                provider,
                externalId,
                title,
                venueName,
                formattedAddress,
                latitude,
                longitude,
                startsOn,
                endsOn,
                detailUri,
                imageUri,
                EventSearchProvenance.tourApi()
            );
        }
    }
}
