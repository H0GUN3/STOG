package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

public class CloudPlaceScheduleNormalizerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void convertsLiveObjectScheduleIntoCanonicalWeeklyAndDateOverrideArrays() throws Exception {
        JsonNode live = json.readTree(
            """
            {
              "mon": [{"open": "09:00", "close": "18:00", "cross_midnight": false}],
              "date_overrides": {
                "2026-08-15": {"closed": true, "windows": []},
                "2026-08-16": {
                  "closed": false,
                  "windows": [{"open": "10:00", "close": "14:00", "cross_midnight": false}]
                }
              }
            }
            """
        );

        CloudPlaceScheduleNormalizer.Schedule normalized =
            CloudPlaceScheduleNormalizer.normalize(live);

        assertThat(normalized.weekly()).hasSize(1);
        assertThat(normalized.weekly().get(0).get("day").asText()).isEqualTo("mon");
        assertThat(normalized.weekly().get(0).get("opens_at").asText()).isEqualTo("09:00");
        assertThat(normalized.weekly().get(0).get("closes_at").asText()).isEqualTo("18:00");
        assertThat(normalized.dateOverrides()).hasSize(2);
        assertThat(normalized.dateOverrides().get(0).get("closed").asBoolean()).isTrue();
        assertThat(normalized.dateOverrides().get(1).get("opens_at").asText())
            .isEqualTo("10:00");
        assertThat(normalized.crossMidnight()).isFalse();
    }
}
