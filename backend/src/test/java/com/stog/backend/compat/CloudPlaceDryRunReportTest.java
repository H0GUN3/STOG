package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

public class CloudPlaceDryRunReportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CloudPlaceNormalizer normalizer = new CloudPlaceNormalizer();

    @Test
    void reportReconcilesEveryNormalizationAndReference() {
        CloudPlaceSourceRow mapped = row(
            "item-1",
            List.of(new CloudPlaceExternalRef("GOOGLE", "google-1"))
        );
        CloudPlaceSourceRow duplicate = row(
            "item-2",
            List.of(
                new CloudPlaceExternalRef("GOOGLE", "google-2"),
                new CloudPlaceExternalRef("GOOGLE", "google-2")
            )
        );

        CloudPlaceDryRunReport report = CloudPlaceDryRunReport.fromNormalizations(
            normalizer.normalizeAll(List.of(duplicate, mapped))
        );

        assertThat(report.rowCount()).isEqualTo(2);
        assertThat(report.classificationCounts()).containsEntry("importable", 2L);
        assertThat(report.externalReferenceCount()).isEqualTo(3);
        assertThat(report.duplicateReferenceCount()).isEqualTo(1);
        assertThat(report.orphanReferenceCount()).isZero();
    }

    private static CloudPlaceSourceRow row(
        String travelItemId,
        List<CloudPlaceExternalRef> refs
    ) {
        return new CloudPlaceSourceRow(
            travelItemId,
            "BUSINESS",
            "장소",
            "설명",
            35.815,
            127.15,
            "주소",
            "TOUR_API",
            travelItemId,
            "ACTIVE",
            null,
            null,
            null,
            null,
            11,
            refs,
            "raw-" + travelItemId,
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
