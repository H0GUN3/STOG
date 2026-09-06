package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

public class CloudPlaceReadRepositoryTest {
    @Test
    void limitsSourceItemsBeforeJoiningReferencesAndReadsCompleteDetails() throws Exception {
        Field field = CloudPlaceReadRepository.class.getDeclaredField("ACTIVE_PLACES_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertThat(sql).contains(
            "WITH source_user_constraints AS",
            "FROM users u",
            "source_items AS",
            "JOIN travel_item_details tid ON tid.item_id = ti.id"
        );
        assertThat(sql).contains("travel_items ti");
        assertThat(sql).contains(
            "opening_hours",
            "date_overrides",
            "visit_minutes_source",
            "visit_minutes_override",
            "traits",
            "user_constraint_arrays",
            "raw_digest",
            "license_decision"
        );
        assertThat(sql).contains("LIMIT :limit");
        assertThat(sql.indexOf("LIMIT :limit"))
            .isLessThan(sql.indexOf("travel_item_external_refs"));
        assertThat(sql).contains("ORDER BY ti.travel_item_id, ref.provider, ref.external_id");
        assertThat(sql).contains(
            "TOUR_API",
            "AREA_RESTAURANT",
            "travel_item_categories",
            "TOUR_CULTURE",
            "TOUR_HISTORY",
            "TOUR_NATURE",
            "TOUR_EXPERIENCE",
            "md5(to_jsonb(ti)::text || '|' || to_jsonb(tid)::text)"
        );
        assertThat(sql.toUpperCase()).doesNotContain("INSERT ", "UPDATE ", "DELETE ", "MERGE ");
    }
}
