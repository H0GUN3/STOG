package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

public class CloudPlaceMapperTest {
    private final CloudPlaceMapper mapper = new CloudPlaceMapper();

    @Test
    void googleExternalReferencePreservesProviderIdentityAndCoordinates() {
        CloudPlaceMapping mapping = mapper.map(new CloudPlaceRow(
            "item-1",
            "BUSINESS",
            "카페",
            "설명",
            35.815,
            127.15,
            "전주시",
            "KAKAO_LOCAL",
            "kakao-1",
            "ACTIVE",
            "OUTDOOR",
            8000,
            "cell-uuid",
            "8a1fb46622dffff",
            11,
            "GOOGLE",
            "ChIJgoogle"
        ));

        assertThat(mapping.travelItemId()).isEqualTo("item-1");
        assertThat(mapping.candidate().provider()).isEqualTo("google");
        assertThat(mapping.candidate().external_id()).isEqualTo("ChIJgoogle");
        assertThat(mapping.candidate().latitude()).isEqualTo(35.815);
        assertThat(mapping.candidate().longitude()).isEqualTo(127.15);
        assertThat(mapping.legacyH3Resolution()).isEqualTo(11);
    }

    @Test
    void sourceItemIdProvidesFallbackIdentityWithoutExternalReference() {
        CloudPlaceMapping mapping = mapper.map(new CloudPlaceRow(
            "item-2",
            "TOURIST_PLACE",
            "관광지",
            null,
            null,
            null,
            null,
            "TOUR_API",
            "tour-2",
            "ACTIVE",
            "OUTDOOR",
            null,
            null,
            null,
            null,
            null,
            null
        ));

        assertThat(mapping.candidate().provider()).isEqualTo("public_data");
        assertThat(mapping.candidate().external_id()).isEqualTo("tour-2");
        assertThat(mapping.candidate().latitude()).isNull();
        assertThat(mapping.candidate().longitude()).isNull();
    }

    @Test
    void unknownProviderDoesNotBecomeUntrustedNormalizedData() {
        CloudPlaceRow row = new CloudPlaceRow(
            "item-3",
            "BUSINESS",
            "장소",
            null,
            35.0,
            127.0,
            null,
            "KAKAO_LOCAL",
            "kakao-3",
            "ACTIVE",
            "UNKNOWN",
            null,
            null,
            null,
            null,
            "UNKNOWN",
            "x"
        );

        assertThatThrownBy(() -> mapper.map(row))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provider");
    }

    @Test
    void dryRunReportCountsRowsMissingCoordinatesAndLegacyResolutions() {
        CloudPlaceDryRunReport report = CloudPlaceDryRunReport.from(List.of(
            mapper.map(new CloudPlaceRow(
                "item-1",
                "BUSINESS",
                "A",
                null,
                35.0,
                127.0,
                null,
                "KAKAO_LOCAL",
                "a",
                "ACTIVE",
                "OUTDOOR",
                null,
                "cell-1",
                "8a1",
                11,
                "KAKAO",
                "a"
            )),
            mapper.map(new CloudPlaceRow(
                "item-2",
                "BUSINESS",
                "B",
                null,
                null,
                null,
                null,
                "TOUR_API",
                "b",
                "ACTIVE",
                "OUTDOOR",
                null,
                null,
                null,
                null,
                null,
                null
            ))
        ));

        assertThat(report.rowCount()).isEqualTo(2);
        assertThat(report.missingCoordinateCount()).isEqualTo(1);
        assertThat(report.sourceCounts())
            .containsEntry("KAKAO_LOCAL", 1L)
            .containsEntry("TOUR_API", 1L);
        assertThat(report.legacyResolutionCounts()).containsEntry(11, 1L);
    }
}
