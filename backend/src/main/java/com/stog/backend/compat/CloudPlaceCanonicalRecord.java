package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Candidate canonical values only; it is data for a later approved importer. */
public record CloudPlaceCanonicalRecord(
    @JsonProperty("provider") String provider,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("category") String category,
    @JsonProperty("latitude") Double latitude,
    @JsonProperty("longitude") Double longitude,
    @JsonProperty("canonical_h3_10") String canonicalCellId,
    @JsonProperty("address") String address,
    @JsonProperty("description") String description,
    @JsonProperty("environment_type") String environmentType,
    @JsonProperty("estimated_cost") Integer estimatedCost,
    @JsonProperty("opening_hours") JsonNode openingHours,
    @JsonProperty("date_overrides") JsonNode dateOverrides,
    @JsonProperty("cross_midnight") Boolean crossMidnight,
    @JsonProperty("visit_minutes") Integer visitMinutes,
    @JsonProperty("visit_minutes_source") String visitMinutesSource,
    @JsonProperty("visit_minutes_override") Integer visitMinutesOverride,
    @JsonProperty("traits") JsonNode traits,
    @JsonProperty("traits_source") String traitsSource,
    @JsonProperty("traits_model") String traitsModel,
    @JsonProperty("traits_updated_at") String traitsUpdatedAt,
    @JsonProperty("source_updated_at") String sourceUpdatedAt,
    @JsonProperty("supplemental_external_references") List<CloudPlaceExternalRef> supplementalExternalReferences,
    @JsonProperty("public_cell_eligible") Boolean publicCellEligible,
    @JsonProperty("source_images") List<CloudPlaceSourceImage> sourceImages
) {
    public CloudPlaceCanonicalRecord {
        supplementalExternalReferences = List.copyOf(supplementalExternalReferences);
        sourceImages = sourceImages == null ? List.of() : List.copyOf(sourceImages);
        openingHours = CloudPlaceJson.stable(openingHours);
        dateOverrides = CloudPlaceJson.stable(dateOverrides);
        traits = CloudPlaceJson.stable(traits);
    }

    static CloudPlaceCanonicalRecord from(CloudPlaceNormalization normalization) {
        CloudPlaceSourceRow row = normalization.sourceRow();
        return new CloudPlaceCanonicalRecord(
            normalization.provider(),
            normalization.externalId(),
            row.name(),
            row.itemType(),
            row.latitude(),
            row.longitude(),
            normalization.canonicalCellId(),
            row.address(),
            row.description(),
            row.environmentType(),
            row.estimatedCost(),
            row.openingHours(),
            row.dateOverrides(),
            row.crossMidnight(),
            row.visitMinutes(),
            row.visitMinutesSource(),
            row.visitMinutesOverride(),
            row.traits(),
            row.traitsSource(),
            row.traitsModel(),
            row.traitsUpdatedAt(),
            row.sourceUpdatedAt(),
            row.externalReferences(),
            normalization.canonicalCellId() != null,
            row.sourceImages()
        );
    }
}
