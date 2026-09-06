package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.storage.GcsSignedUrlService;
import com.stog.backend.storage.PhotoController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

public class CloudCompatibilityBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CloudPlaceNormalizer normalizer = new CloudPlaceNormalizer();

    @Test
    void quarantinesNonFiniteAndOutOfRangeCoordinatesWithoutCalculatingCells() {
        for (CloudPlaceSourceRow row : List.of(
            row(Double.NaN, 127.15, "ACTIVE", "kakao-1", List.of()),
            row(35.815, Double.POSITIVE_INFINITY, "ACTIVE", "kakao-1", List.of()),
            row(91.0, 127.15, "ACTIVE", "kakao-1", List.of()),
            row(35.815, -181.0, "ACTIVE", "kakao-1", List.of())
        )) {
            CloudPlaceNormalization result = normalizer.normalize(row);

            assertThat(result.classification()).isEqualTo("quarantined");
            assertThat(result.reason()).isEqualTo("invalid_coordinates");
            assertThat(result.canonicalCellId()).isNull();
        }
    }

    @Test
    void preservesInactiveRowsAndQuarantinesMissingIdentity() {
        CloudPlaceNormalization missingIdentity = normalizer.normalize(
            row(35.815, 127.15, "ACTIVE", null, List.of())
        );
        CloudPlaceNormalization inactive = normalizer.normalize(
            row(35.815, 127.15, "INACTIVE", "kakao-2", List.of())
        );

        assertThat(missingIdentity.classification()).isEqualTo("quarantined");
        assertThat(missingIdentity.reason()).isEqualTo("missing_external_id");
        assertThat(inactive.classification()).isEqualTo("preserved_only");
        assertThat(inactive.reason()).isEqualTo("inactive_source_row");
        assertThat(inactive.diagnostics()).contains("non_active_source_row");
    }

    @Test
    void quarantinesUnsupportedAndMixedCaseProviderReferences() {
        for (CloudPlaceSourceRow row : List.of(
            row(
                35.815,
                127.15,
                "ACTIVE",
                "kakao-3",
                List.of(new CloudPlaceExternalRef("UNKNOWN", "external-1"))
            ),
            row(
                35.815,
                127.15,
                "ACTIVE",
                "kakao-4",
                List.of(new CloudPlaceExternalRef("google", "external-2"))
            )
        )) {
            CloudPlaceNormalization result = normalizer.normalize(row);

            assertThat(result.classification()).isEqualTo("quarantined");
            assertThat(result.reason()).isEqualTo("unsupported_provider");
            assertThat(result.canonicalCellId()).isNull();
        }
    }

    @Test
    void cloudAndGcsWritersRequireExplicitWriteProfiles() {
        assertThat(CloudPlaceWriteRepository.class.getAnnotation(Profile.class).value())
            .containsExactly("cloud-write");
        assertThat(GcsSignedUrlService.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-write");
        assertThat(PhotoController.class.getAnnotation(Profile.class).value())
            .containsExactly("gcs-write");
    }

    private CloudPlaceSourceRow row(
        Double latitude,
        Double longitude,
        String status,
        String sourceItemId,
        List<CloudPlaceExternalRef> references
    ) {
        return new CloudPlaceSourceRow(
            "item",
            "BUSINESS",
            "fixture",
            null,
            latitude,
            longitude,
            null,
            "KAKAO_LOCAL",
            sourceItemId,
            status,
            "OUTDOOR",
            null,
            null,
            "8a1fb46622dffff",
            11,
            references,
            "raw-item",
            "APPROVED_PUBLIC_REUSE",
            List.of("name"),
            json("[]"),
            json("[]"),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            json("[]")
        );
    }

    private static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
