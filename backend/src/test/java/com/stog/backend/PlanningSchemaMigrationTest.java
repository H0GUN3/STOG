package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class PlanningSchemaMigrationTest {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatesCanonicalPlanningTables() {
        List<String> tables = jdbc.sql(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('trips', 'places', 'basket_items', 'itinerary_items', 'itinerary_changes')
                ORDER BY table_name
                """
            )
            .query(String.class)
            .list();

        assertThat(tables).containsExactly(
            "basket_items",
            "itinerary_changes",
            "itinerary_items",
            "places",
            "trips"
        );
    }

    @Test
    void planningTablesKeepCanonicalKeysAndStatuses() {
        List<String> columns = jdbc.sql(
                """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (
                    (table_name = 'places' AND column_name IN ('source', 'external_id', 'cell_id'))
                    OR (table_name = 'basket_items' AND column_name IN ('trip_id', 'added_by', 'place_id', 'status'))
                    OR (table_name = 'trips' AND column_name = 'region_code')
                  )
                ORDER BY 1
                """
            )
            .query(String.class)
            .list();

        assertThat(columns).containsExactly(
            "basket_items.added_by",
            "basket_items.place_id",
            "basket_items.status",
            "basket_items.trip_id",
            "places.cell_id",
            "places.external_id",
            "places.source",
            "trips.region_code"
        );
    }

    @Test
    void tripsDefaultToPublicVisibility() {
        String visibilityDefault = jdbc.sql(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'trips'
                  AND column_name = 'visibility'
                """
            )
            .query(String.class)
            .single();

        assertThat(visibilityDefault).isEqualTo("'public'::text");
    }
}
