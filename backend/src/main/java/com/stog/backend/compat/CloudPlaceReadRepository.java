package com.stog.backend.compat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CloudPlaceReadRepository {
    private static final String ACTIVE_PLACES_SQL = """
        WITH source_user_constraints AS (
            SELECT COALESCE(
                jsonb_agg(
                    jsonb_build_object(
                        'source_user_uuid', u.id::text,
                        'arrays', COALESCE((
                            SELECT jsonb_object_agg(attribute.key, attribute.value)
                            FROM jsonb_each(to_jsonb(u)) AS attribute(key, value)
                            WHERE jsonb_typeof(attribute.value) = 'array'
                        ), '{}'::jsonb)
                    )
                    ORDER BY u.id
                ),
                '[]'::jsonb
            ) AS user_constraint_arrays
            FROM users u
        ),
        source_items AS (
            SELECT
                ti.id::text AS travel_item_id,
                ti.item_type,
                ti.name,
                ti.description,
                ST_Y(ti.location::geometry) AS latitude,
                ST_X(ti.location::geometry) AS longitude,
                ti.address,
                tid.source,
                tid.source_item_id,
                EXISTS (
                    SELECT 1
                    FROM travel_item_categories tic
                    JOIN categories source_category ON source_category.id = tic.category_id
                    WHERE tic.item_id = ti.id
                      AND tic.is_primary
                      AND source_category.code IN (
                          'TOUR_CULTURE',
                          'TOUR_HISTORY',
                          'TOUR_NATURE',
                          'TOUR_EXPERIENCE'
                      )
                ) AS public_cell_eligible,
                ti.status,
                tid.environment_type,
                tid.estimated_cost,
                ti.cell_id::text AS legacy_cell_id,
                c.h3_index AS legacy_h3_index,
                c.h3_resolution AS legacy_h3_resolution,
                md5(to_jsonb(ti)::text || '|' || to_jsonb(tid)::text) AS raw_digest,
                CASE
                    WHEN tid.source IN ('TOUR_API', 'AREA_RESTAURANT')
                    THEN 'APPROVED_PUBLIC_REUSE'
                    ELSE 'UNREVIEWED'
                END AS license_decision,
                CASE
                    WHEN tid.source IN ('TOUR_API', 'AREA_RESTAURANT')
                    THEN ARRAY[
                        'address', 'description', 'name', 'opening_hours', 'visit_minutes'
                    ]::text[]
                    ELSE ARRAY[]::text[]
                END AS reusable_fields,
                tid.opening_hours,
                NULL::jsonb AS date_overrides,
                NULL::boolean AS cross_midnight,
                tid.visit_minutes,
                tid.visit_minutes_source,
                tid.visit_minutes_override,
                NULL::jsonb AS traits,
                NULL::text AS traits_source,
                NULL::text AS traits_model,
                NULL::text AS traits_updated_at,
                tid.source_updated_at::text AS source_updated_at,
                source_user_constraints.user_constraint_arrays
            FROM travel_items ti
            JOIN travel_item_details tid ON tid.item_id = ti.id
            CROSS JOIN source_user_constraints
            LEFT JOIN cells c ON c.id = ti.cell_id
            WHERE tid.source IN ('TOUR_API', 'AREA_RESTAURANT')
            ORDER BY ti.id
            LIMIT :limit
        )
        SELECT
            ti.*,
            ref.provider AS external_provider,
            ref.external_id
        FROM source_items ti
        LEFT JOIN travel_item_external_refs ref
          ON ref.travel_item_id::text = ti.travel_item_id
        ORDER BY ti.travel_item_id, ref.provider, ref.external_id
        """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final CloudPlaceReconciler reconciler = new CloudPlaceReconciler();

    public CloudPlaceReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Legacy method name retained for callers. The manifest intentionally reads
     * every selected source row, including inactive rows that become
     * {@code preserved_only}, before joining external references.
     */
    @Transactional(readOnly = true)
    public CloudPlaceReconciliationResult findActive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<CloudPlaceRow> rows = jdbc.sql(ACTIVE_PLACES_SQL)
            .param("limit", limit)
            .query((row, rowNumber) -> {
                CloudPlaceScheduleNormalizer.Schedule schedule =
                    CloudPlaceScheduleNormalizer.normalize(json(row.getString("opening_hours")));
                return new CloudPlaceRow(
                    row.getString("travel_item_id"),
                    row.getString("item_type"),
                    row.getString("name"),
                    row.getString("description"),
                    row.getObject("latitude", Double.class),
                    row.getObject("longitude", Double.class),
                    row.getString("address"),
                    row.getString("source"),
                    row.getString("source_item_id"),
                    row.getString("status"),
                    row.getString("environment_type"),
                    row.getObject("estimated_cost", Integer.class),
                    row.getString("legacy_cell_id"),
                    row.getString("legacy_h3_index"),
                    row.getObject("legacy_h3_resolution", Integer.class),
                    row.getString("external_provider"),
                    row.getString("external_id"),
                    row.getString("raw_digest"),
                    row.getString("license_decision"),
                    stringArray(row, "reusable_fields"),
                    schedule.weekly(),
                    schedule.dateOverrides(),
                    schedule.crossMidnight(),
                    row.getObject("visit_minutes", Integer.class),
                    row.getString("visit_minutes_source"),
                    row.getObject("visit_minutes_override", Integer.class),
                    json(row.getString("traits")),
                    row.getString("traits_source"),
                    row.getString("traits_model"),
                    row.getString("traits_updated_at"),
                    row.getString("source_updated_at"),
                    json(row.getString("user_constraint_arrays")),
                    row.getObject("public_cell_eligible", Boolean.class)
                );
            })
            .list();
        return reconciler.reconcile(rows);
    }

    @Transactional(readOnly = true)
    public long countOrphanExternalReferences() {
        return jdbc.sql(
                """
                SELECT COUNT(*)
                FROM travel_item_external_refs ref
                LEFT JOIN travel_items ti ON ti.id = ref.travel_item_id
                WHERE ti.id IS NULL
                """
            )
            .query(Long.class)
            .single();
    }

    @Transactional(readOnly = true)
    public long countDuplicateExternalReferences() {
        return jdbc.sql(
                """
                SELECT COALESCE(SUM(reference_count - 1), 0)
                FROM (
                    SELECT COUNT(*) AS reference_count
                    FROM travel_item_external_refs
                    GROUP BY travel_item_id, provider, external_id
                    HAVING COUNT(*) > 1
                ) duplicates
                """
            )
            .query(Long.class)
            .single();
    }

    private static JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("legacy JSON field is malformed", error);
        }
    }

    private static List<String> stringArray(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            Object values = array.getArray();
            if (!(values instanceof Object[] entries)) {
                throw new IllegalStateException("legacy array field is not an object array");
            }
            return Arrays.stream(entries).map(String::valueOf).sorted().toList();
        } finally {
            array.free();
        }
    }
}
