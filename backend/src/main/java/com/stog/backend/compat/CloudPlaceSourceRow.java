package com.stog.backend.compat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;

/**
 * One read-only legacy {@code travel_items} record together with its optional
 * {@code travel_item_details} record and all external references. No field in
 * this DTO is a canonical database identity.
 */
public record CloudPlaceSourceRow(
    @JsonProperty("travel_item_id") String travelItemId,
    @JsonProperty("item_type") String itemType,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("latitude") Double latitude,
    @JsonProperty("longitude") Double longitude,
    @JsonProperty("address") String address,
    @JsonProperty("source") String source,
    @JsonProperty("source_item_id") String sourceItemId,
    @JsonProperty("status") String status,
    @JsonProperty("environment_type") String environmentType,
    @JsonProperty("estimated_cost") Integer estimatedCost,
    @JsonProperty("legacy_cell_id") String legacyCellId,
    @JsonProperty("legacy_h3_index") String legacyH3Index,
    @JsonProperty("legacy_h3_resolution") Integer legacyH3Resolution,
    @JsonProperty("external_references") List<CloudPlaceExternalRef> externalReferences,
    @JsonProperty("raw_digest") String rawDigest,
    @JsonProperty("license_decision") String licenseDecision,
    @JsonProperty("reusable_fields") List<String> reusableFields,
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
    @JsonProperty("user_constraint_arrays") JsonNode userConstraintArrays,
    @JsonProperty("public_cell_eligible") Boolean publicCellEligible,
    @JsonProperty("source_images") List<CloudPlaceSourceImage> sourceImages
) {
    public CloudPlaceSourceRow {
        externalReferences = externalReferences == null
            ? List.of()
            : externalReferences.stream()
                .sorted(Comparator
                    .comparing(CloudPlaceExternalRef::provider, Comparator.nullsFirst(String::compareTo))
                    .thenComparing(CloudPlaceExternalRef::externalId, Comparator.nullsFirst(String::compareTo)))
                .toList();
        reusableFields = reusableFields == null
            ? List.of()
            : reusableFields.stream().sorted().toList();
        openingHours = CloudPlaceJson.stable(openingHours);
        dateOverrides = CloudPlaceJson.stable(dateOverrides);
        traits = CloudPlaceJson.stable(traits);
        userConstraintArrays = CloudPlaceJson.stable(userConstraintArrays);
        sourceImages = sourceImages == null
            ? List.of()
            : sourceImages.stream()
                .sorted(Comparator
                    .comparing(
                        CloudPlaceSourceImage::sourceImageId,
                        Comparator.nullsFirst(String::compareTo)
                    )
                    .thenComparing(
                        CloudPlaceSourceImage::sourceDigest,
                        Comparator.nullsFirst(String::compareTo)
                    ))
                .toList();
    }

    /** Compatibility constructor for the pre-eligibility source projection. */
    public CloudPlaceSourceRow(
        String travelItemId,
        String itemType,
        String name,
        String description,
        Double latitude,
        Double longitude,
        String address,
        String source,
        String sourceItemId,
        String status,
        String environmentType,
        Integer estimatedCost,
        String legacyCellId,
        String legacyH3Index,
        Integer legacyH3Resolution,
        List<CloudPlaceExternalRef> externalReferences,
        String rawDigest,
        String licenseDecision,
        List<String> reusableFields,
        JsonNode openingHours,
        JsonNode dateOverrides,
        Boolean crossMidnight,
        Integer visitMinutes,
        String visitMinutesSource,
        Integer visitMinutesOverride,
        JsonNode traits,
        String traitsSource,
        String traitsModel,
        String traitsUpdatedAt,
        String sourceUpdatedAt,
        JsonNode userConstraintArrays
    ) {
        this(
            travelItemId,
            itemType,
            name,
            description,
            latitude,
            longitude,
            address,
            source,
            sourceItemId,
            status,
            environmentType,
            estimatedCost,
            legacyCellId,
            legacyH3Index,
            legacyH3Resolution,
            externalReferences,
            rawDigest,
            licenseDecision,
            reusableFields,
            openingHours,
            dateOverrides,
            crossMidnight,
            visitMinutes,
            visitMinutesSource,
            visitMinutesOverride,
            traits,
            traitsSource,
            traitsModel,
            traitsUpdatedAt,
            sourceUpdatedAt,
            userConstraintArrays,
            null,
            List.of()
        );
    }

    /** Compatibility constructor for the pre-detail extraction contract. */
    public CloudPlaceSourceRow(
        String travelItemId,
        String itemType,
        String name,
        String description,
        Double latitude,
        Double longitude,
        String address,
        String source,
        String sourceItemId,
        String status,
        String environmentType,
        Integer estimatedCost,
        String legacyCellId,
        String legacyH3Index,
        Integer legacyH3Resolution,
        List<CloudPlaceExternalRef> externalReferences
    ) {
        this(
            travelItemId,
            itemType,
            name,
            description,
            latitude,
            longitude,
            address,
            source,
            sourceItemId,
            status,
            environmentType,
            estimatedCost,
            legacyCellId,
            legacyH3Index,
            legacyH3Resolution,
            externalReferences,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of()
        );
    }

    public static CloudPlaceSourceRow from(
        CloudPlaceRow row,
        List<CloudPlaceExternalRef> externalReferences
    ) {
        return new CloudPlaceSourceRow(
            row.travelItemId(),
            row.itemType(),
            row.name(),
            row.description(),
            row.latitude(),
            row.longitude(),
            row.address(),
            row.source(),
            row.sourceItemId(),
            row.status(),
            row.environmentType(),
            row.estimatedCost(),
            row.legacyCellId(),
            row.legacyH3Index(),
            row.legacyH3Resolution(),
            externalReferences,
            row.rawDigest(),
            row.licenseDecision(),
            row.reusableFields(),
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
            row.userConstraintArrays(),
            row.publicCellEligible(),
            List.of()
        );
    }
}
