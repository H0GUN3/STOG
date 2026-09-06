package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

public class CloudPlaceReconcilerTest {
    private final CloudPlaceReconciler reconciler = new CloudPlaceReconciler();

    @Test
    void groupsReferenceRowsIntoOneSourceRowPerTravelItem() {
        CloudPlaceReconciliationResult result = reconciler.reconcile(List.of(
            row("item-2", "KAKAO", "kakao-2"),
            row("item-1", "KAKAO", "kakao-1"),
            row("item-1", "GOOGLE", "google-1")
        ));

        assertThat(result.inputRowCount()).isEqualTo(3);
        assertThat(result.rows())
            .extracting(CloudPlaceSourceRow::travelItemId)
            .containsExactly("item-1", "item-2");
        assertThat(result.rows().get(0).externalReferences())
            .containsExactly(
                new CloudPlaceExternalRef("GOOGLE", "google-1"),
                new CloudPlaceExternalRef("KAKAO", "kakao-1")
            );
        assertThat(result.resultRowCount()).isEqualTo(2);
        assertThat(result.duplicateReferenceCount()).isZero();
        assertThat(result.orphanReferenceCount()).isZero();
    }

    @Test
    void reportsDuplicateAndOrphanReferencesWithoutDroppingSourceRows() {
        CloudPlaceReconciliationResult result = reconciler.reconcile(List.of(
            row("item-1", "KAKAO", "kakao-1"),
            row("item-1", "KAKAO", "kakao-1"),
            row(null, "GOOGLE", "orphan")
        ));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).externalReferences()).hasSize(1);
        assertThat(result.duplicateReferenceCount()).isEqualTo(1);
        assertThat(result.orphanReferenceCount()).isEqualTo(1);
    }

    @Test
    void fixtureReportNeedsNoCloudDatasourceAndRepositoryReadsAreReadOnly() throws Exception {
        CloudPlaceDryRunReport report = new CloudPlaceDryRunService(null)
            .previewFixture(List.of(new CloudPlaceReconciler().reconcile(List.of(
                row("item-1", "KAKAO", "kakao-1")
            )).rows().get(0)));

        assertThat(report.rowCount()).isEqualTo(1);
        Transactional transaction = CloudPlaceReadRepository.class
            .getMethod("findActive", int.class)
            .getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
    }

    private CloudPlaceRow row(String travelItemId, String provider, String externalId) {
        return new CloudPlaceRow(
            travelItemId,
            "BUSINESS",
            "fixture",
            null,
            35.815,
            127.15,
            null,
            "KAKAO_LOCAL",
            "kakao-1",
            "ACTIVE",
            "OUTDOOR",
            null,
            "legacy-cell",
            "8a1fb46622dffff",
            11,
            provider,
            externalId
        );
    }
}
