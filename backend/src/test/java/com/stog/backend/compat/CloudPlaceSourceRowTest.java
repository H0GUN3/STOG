package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CloudPlaceSourceRowTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fixturePreservesTheCompleteLegacyPlaceAndDetailShape() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream(
            "/compat/cloud-place-fixture.json"
        )) {
            assertThat(fixture).isNotNull();
            List<CloudPlaceSourceRow> rows = json.readValue(
                fixture,
                new TypeReference<>() {}
            );

            assertThat(rows).hasSize(10);
            CloudPlaceSourceRow row = rows.get(0);
            assertThat(row.travelItemId()).isEqualTo("11111111-1111-1111-1111-111111111111");
            assertThat(row.rawDigest()).isEqualTo("raw-digest-tour-1");
            assertThat(row.licenseDecision()).isEqualTo("APPROVED_PUBLIC_REUSE");
            assertThat(row.externalReferences())
                .extracting(CloudPlaceExternalRef::provider, CloudPlaceExternalRef::externalId)
                .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("GOOGLE", "ChIJ-tour-1"),
                    org.assertj.core.groups.Tuple.tuple("KAKAO", "kakao-tour-1")
                );
            assertThat(row.openingHours()).hasSize(2);
            assertThat(row.dateOverrides()).hasSize(1);
            assertThat(row.visitMinutes()).isEqualTo(45);
            assertThat(row.visitMinutesOverride()).isEqualTo(60);
            assertThat(row.traits()).hasSize(6);
            assertThat(row.userConstraintArrays()).isNotNull();
        }
    }

    @Test
    void fixtureKeepsDifferentVisitAndTraitValuesInsteadOfSynthesizingDefaults() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream(
            "/compat/cloud-place-fixture.json"
        )) {
            List<CloudPlaceSourceRow> rows = json.readValue(fixture, new TypeReference<>() {});

            assertThat(rows.subList(0, 2))
                .extracting(CloudPlaceSourceRow::visitMinutes)
                .containsExactly(45, 120);
            assertThat(rows.get(0).traits()).isNotEqualTo(rows.get(1).traits());
            assertThat(rows.get(2).latitude()).isNull();
            assertThat(rows.get(2).legacyH3Resolution()).isNull();
        }
    }
}
