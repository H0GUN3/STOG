package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CloudPlaceNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CloudPlaceNormalizer normalizer = new CloudPlaceNormalizer();

    @Test
    void mapsApprovedSourceWithoutTourismEvidenceButDoesNotAssignCell() {
        CloudPlaceNormalization result = normalizer.normalize(row(
            "item-1", "전주 카페", 35.815, 127.15, "KAKAO_LOCAL", "kakao-1", List.of()
        ));

        assertThat(result.classification()).isEqualTo("importable");
        assertThat(result.provider()).isEqualTo("kakao");
        assertThat(result.externalId()).isEqualTo("kakao-1");
        assertThat(result.canonicalCellId()).isNull();
        assertThat(result.sourceRow().source()).isEqualTo("KAKAO_LOCAL");
    }

    @Test
    void calculatesCanonicalH3TenOnlyForEligibleTourismRows() {
        CloudPlaceNormalization result = normalizer.normalize(row(
            "item-tourism",
            "전주 한옥마을",
            35.815,
            127.15,
            "TOUR_API",
            "tourism-1",
            List.of(),
            "APPROVED_PUBLIC_REUSE",
            "TOURIST_PLACE",
            true
        ));

        assertThat(result.classification()).isEqualTo("importable");
        assertThat(result.canonicalCellId()).isNotBlank();
    }

    @Test
    void preservesMultipleSupportedReferencesAndStillUsesTheSourceIdentity() {
        CloudPlaceNormalization result = normalizer.normalize(row(
            "item-2",
            "전주 카페",
            35.815,
            127.15,
            "TOUR_API",
            "tour-2",
            List.of(
                new CloudPlaceExternalRef("KAKAO", "kakao-2"),
                new CloudPlaceExternalRef("GOOGLE", "google-2")
            )
        ));

        assertThat(result.classification()).isEqualTo("importable");
        assertThat(result.provider()).isEqualTo("public_data");
        assertThat(result.externalId()).isEqualTo("tour-2");
        assertThat(result.sourceRow().externalReferences()).hasSize(2);
        assertThat(result.diagnostics()).contains("multiple_external_references_preserved");
    }

    @Test
    void acceptsLivePublicSourceReferenceAndBasicImportWithoutTraitsOrSourceTimestamp() {
        CloudPlaceSourceRow source = new CloudPlaceSourceRow(
            "live-item-1",
            "TOURIST_PLACE",
            "전주 장소",
            "description",
            35.815,
            127.15,
            "전북 전주시",
            "TOUR_API",
            "tour-1",
            "ACTIVE",
            "OUTDOOR",
            0,
            "legacy-cell",
            "legacy-h3",
            11,
            List.of(new CloudPlaceExternalRef("TOUR_API", "tour-1")),
            "raw-live-item-1",
            "APPROVED_PUBLIC_REUSE",
            List.of("address", "description", "name", "opening_hours", "visit_minutes"),
            array("[{\"day\":\"mon\",\"opens_at\":\"09:00\",\"closes_at\":\"18:00\"}]"),
            array("[]"),
            false,
            45,
            "source",
            null,
            null,
            null,
            null,
            null,
            null,
            array("[]")
        );

        CloudPlaceNormalization result = normalizer.normalize(source);

        assertThat(result.classification()).isEqualTo("importable");
        assertThat(result.provider()).isEqualTo("public_data");
        assertThat(result.externalId()).isEqualTo("tour-1");
    }

    @Test
    void quarantinesMissingInvalidAndOutOfRegionCoordinatesWithoutCells() {
        CloudPlaceNormalization missing = normalizer.normalize(row(
            "item-3", "장소", null, null, "TOUR_API", "tour-3", List.of()
        ));
        CloudPlaceNormalization partial = normalizer.normalize(row(
            "item-4", "장소", 35.815, null, "TOUR_API", "tour-4", List.of()
        ));
        CloudPlaceNormalization outOfRegion = normalizer.normalize(row(
            "item-5", "장소", 37.5665, 126.978, "TOUR_API", "tour-5", List.of()
        ));

        assertThat(missing.classification()).isEqualTo("quarantined");
        assertThat(missing.reason()).isEqualTo("missing_coordinates");
        assertThat(partial.reason()).isEqualTo("invalid_coordinates");
        assertThat(outOfRegion.reason()).isEqualTo("out_of_region_coordinates");
        assertThat(missing.canonicalCellId()).isNull();
        assertThat(partial.canonicalCellId()).isNull();
        assertThat(outOfRegion.canonicalCellId()).isNull();
    }

    @Test
    void quarantinesUnsupportedSourceProviderAndMissingName() {
        CloudPlaceNormalization unsupportedSource = normalizer.normalize(row(
            "item-6", "장소", 35.815, 127.15, "UNKNOWN", "unknown-6", List.of()
        ));
        CloudPlaceNormalization unsupportedProvider = normalizer.normalize(row(
            "item-7",
            "장소",
            35.815,
            127.15,
            "KAKAO_LOCAL",
            "kakao-7",
            List.of(new CloudPlaceExternalRef("UNKNOWN", "external-7"))
        ));
        CloudPlaceNormalization missingName = normalizer.normalize(row(
            "item-8", " ", 35.815, 127.15, "AREA_RESTAURANT", "area-8", List.of()
        ));

        assertThat(unsupportedSource.reason()).isEqualTo("unsupported_source");
        assertThat(unsupportedProvider.reason()).isEqualTo("unsupported_provider");
        assertThat(missingName.reason()).isEqualTo("missing_name");
        assertThat(unsupportedSource.classification()).isEqualTo("quarantined");
    }

    @Test
    void defaultsUnknownAndForbiddenReuseRightsToQuarantine() {
        CloudPlaceNormalization unknown = normalizer.normalize(row(
            "item-9", "장소", 35.815, 127.15, "TOUR_API", "tour-9", List.of(), "UNREVIEWED"
        ));
        CloudPlaceNormalization forbidden = normalizer.normalize(row(
            "item-10", "장소", 35.815, 127.15, "TOUR_API", "tour-10", List.of(), "FORBIDDEN"
        ));

        assertThat(unknown.reason()).isEqualTo("unreviewed_reuse_rights");
        assertThat(forbidden.reason()).isEqualTo("forbidden_reuse_rights");
        assertThat(unknown.canonicalCellId()).isNull();
        assertThat(forbidden.canonicalCellId()).isNull();
    }

    @Test
    void normalizesRowsInStableSourceIdOrderAndQuarantinesDuplicateExternalIdentities() {
        List<CloudPlaceNormalization> results = normalizer.normalizeAll(List.of(
            row(
                "item-2", "둘", 35.0, 127.0, "KAKAO_LOCAL", "two",
                List.of(new CloudPlaceExternalRef("KAKAO", "duplicate"))
            ),
            row(
                "item-1", "하나", 35.0, 127.0, "KAKAO_LOCAL", "one",
                List.of(new CloudPlaceExternalRef("KAKAO", "duplicate"))
            )
        ));

        assertThat(results)
            .extracting(result -> result.sourceRow().travelItemId())
            .containsExactly("item-1", "item-2");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.classification()).isEqualTo("quarantined");
            assertThat(result.reason()).isEqualTo("duplicate_external_identity");
        });

        List<CloudPlaceNormalization> duplicateSourceIds = normalizer.normalizeAll(List.of(
            row("item-3", "셋", 35.0, 127.0, "TOUR_API", "same-source-id", List.of()),
            row("item-4", "넷", 35.0, 127.0, "TOUR_API", "same-source-id", List.of())
        ));
        assertThat(duplicateSourceIds).allSatisfy(result ->
            assertThat(result.reason()).isEqualTo("duplicate_external_identity")
        );
    }

    private static CloudPlaceSourceRow row(
        String travelItemId,
        String name,
        Double latitude,
        Double longitude,
        String source,
        String sourceItemId,
        List<CloudPlaceExternalRef> refs
    ) {
        return row(travelItemId, name, latitude, longitude, source, sourceItemId, refs,
            "APPROVED_PUBLIC_REUSE", "BUSINESS", null);
    }

    private static CloudPlaceSourceRow row(
        String travelItemId,
        String name,
        Double latitude,
        Double longitude,
        String source,
        String sourceItemId,
        List<CloudPlaceExternalRef> refs,
        String licenseDecision
    ) {
        return row(
            travelItemId,
            name,
            latitude,
            longitude,
            source,
            sourceItemId,
            refs,
            licenseDecision,
            "BUSINESS",
            null
        );
    }

    private static CloudPlaceSourceRow row(
        String travelItemId,
        String name,
        Double latitude,
        Double longitude,
        String source,
        String sourceItemId,
        List<CloudPlaceExternalRef> refs,
        String licenseDecision,
        String itemType,
        Boolean publicCellEligible
    ) {
        return new CloudPlaceSourceRow(
            travelItemId,
            itemType,
            name,
            "description",
            latitude,
            longitude,
            "address",
            source,
            sourceItemId,
            "ACTIVE",
            "OUTDOOR",
            1000,
            "legacy-cell",
            "legacy-h3",
            11,
            refs,
            "raw-" + travelItemId,
            licenseDecision,
            List.of("name"),
            array("[{\"day\":\"monday\",\"opens_at\":\"09:00\",\"closes_at\":\"18:00\"}]"),
            array("[]"),
            false,
            45,
            "source",
            null,
            traits(),
            "source",
            "traits-v1",
            "2026-08-19T10:00:00Z",
            "2026-08-19T09:00:00Z",
            array("[]"),
            publicCellEligible,
            List.of()
        );
    }

    private static JsonNode array(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static JsonNode traits() {
        return array("{\"nature\":1,\"history\":1,\"local\":1,\"food\":1,\"festival\":1,\"record\":1}");
    }
}
