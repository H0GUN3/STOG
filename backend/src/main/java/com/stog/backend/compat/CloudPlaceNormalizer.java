package com.stog.backend.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stog.backend.cell.CellIdCalculator;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure, deterministic normalization for the read-only legacy manifest. */
public final class CloudPlaceNormalizer {
    private static final Set<String> TRAIT_AXES = Set.of(
        "nature", "history", "local", "food", "festival", "record"
    );
    private static final Pattern TIME = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final Set<String> TOURISM_CELL_ITEM_TYPES = Set.of(
        "ATTRACTION",
        "TOURIST_PLACE",
        "TOUR_CULTURE",
        "TOUR_HISTORY",
        "TOUR_NATURE",
        "TOUR_EXPERIENCE"
    );

    // The approved initial catalog region is Jeonbuk. This inclusive envelope
    // intentionally validates the source boundary before an H3 value is computed.
    private static final double JEONBUK_MIN_LATITUDE = 35.0;
    private static final double JEONBUK_MAX_LATITUDE = 36.3;
    private static final double JEONBUK_MIN_LONGITUDE = 126.2;
    private static final double JEONBUK_MAX_LONGITUDE = 128.0;

    public List<CloudPlaceNormalization> normalizeAll(
        List<CloudPlaceSourceRow> rows
    ) {
        Objects.requireNonNull(rows, "rows");
        List<CloudPlaceSourceRow> sortedRows = rows.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(
                CloudPlaceSourceRow::travelItemId,
                Comparator.nullsLast(Comparator.naturalOrder())
            ))
            .toList();
        Set<String> duplicateIdentities = duplicateExternalIdentities(sortedRows);
        return sortedRows.stream()
            .map(row -> normalize(row, duplicateIdentities))
            .toList();
    }

    public CloudPlaceNormalization normalize(CloudPlaceSourceRow row) {
        return normalize(Objects.requireNonNull(row, "row"), Set.of());
    }

    private CloudPlaceNormalization normalize(
        CloudPlaceSourceRow row,
        Set<String> duplicateIdentities
    ) {
        List<String> diagnostics = diagnostics(row, duplicateIdentities);
        Identity identity = resolveIdentity(row);

        if (!allowsPublicReuse(row.licenseDecision())) {
            return result(
                row,
                "quarantined",
                reuseReason(row.licenseDecision()),
                identity,
                null,
                diagnostics
            );
        }
        if (!"ACTIVE".equals(row.status())) {
            return result(row, "preserved_only", "inactive_source_row", identity, null, diagnostics);
        }
        if (row.rawDigest() == null || row.rawDigest().isBlank()) {
            return result(row, "quarantined", "missing_raw_digest", identity, null, diagnostics);
        }
        if (identity.reason() != null) {
            return result(row, "quarantined", identity.reason(), identity, null, diagnostics);
        }
        if (row.name() == null || row.name().isBlank()) {
            return result(row, "quarantined", "missing_name", identity, null, diagnostics);
        }
        CoordinateStatus coordinates = coordinateStatus(row.latitude(), row.longitude());
        if (coordinates != CoordinateStatus.VALID) {
            return result(row, "quarantined", coordinates.reason, identity, null, diagnostics);
        }
        if (!isInJeonbuk(row.latitude(), row.longitude())) {
            return result(row, "quarantined", "out_of_region_coordinates", identity, null, diagnostics);
        }
        if (hasDuplicateIdentity(row, duplicateIdentities)) {
            return result(row, "quarantined", "duplicate_external_identity", identity, null, diagnostics);
        }
        String invalidDetail = invalidDetailReason(row);
        if (invalidDetail != null) {
            return result(row, "quarantined", invalidDetail, identity, null, diagnostics);
        }
        return result(
            row,
            "importable",
            null,
            identity,
            publicCellEligible(row) ? cell(row) : null,
            diagnostics
        );
    }

    private CloudPlaceNormalization result(
        CloudPlaceSourceRow row,
        String classification,
        String reason,
        Identity identity,
        String canonicalCellId,
        List<String> diagnostics
    ) {
        return new CloudPlaceNormalization(
            row,
            classification,
            reason,
            identity.provider(),
            identity.externalId(),
            canonicalCellId,
            diagnostics
        );
    }

    private Identity resolveIdentity(CloudPlaceSourceRow row) {
        String provider = sourceProvider(row.source());
        if (provider == null) {
            return new Identity(null, null, "unsupported_source");
        }
        if (row.sourceItemId() == null || row.sourceItemId().isBlank()) {
            return new Identity(provider, null, "missing_external_id");
        }
        for (CloudPlaceExternalRef ref : row.externalReferences()) {
            if (externalProvider(ref.provider()) == null) {
                return new Identity(provider, row.sourceItemId(), "unsupported_provider");
            }
            if (ref.externalId() == null || ref.externalId().isBlank()) {
                return new Identity(provider, row.sourceItemId(), "missing_external_id");
            }
        }
        return new Identity(provider, row.sourceItemId(), null);
    }

    private Set<String> duplicateExternalIdentities(List<CloudPlaceSourceRow> rows) {
        Map<String, Set<String>> sourceIdsByExternalIdentity = new HashMap<>();
        for (CloudPlaceSourceRow row : rows) {
            for (String identity : externalIdentities(row)) {
                sourceIdsByExternalIdentity
                    .computeIfAbsent(identity, ignored -> new HashSet<>())
                    .add(String.valueOf(row.travelItemId()));
            }
        }
        Set<String> duplicates = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : sourceIdsByExternalIdentity.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.add(entry.getKey());
            }
        }
        return Set.copyOf(duplicates);
    }

    private boolean hasDuplicateIdentity(
        CloudPlaceSourceRow row,
        Set<String> duplicateIdentities
    ) {
        return externalIdentities(row).stream().anyMatch(duplicateIdentities::contains);
    }

    private List<String> externalIdentities(CloudPlaceSourceRow row) {
        List<String> identities = new ArrayList<>();
        String sourceProvider = sourceProvider(row.source());
        if (sourceProvider != null && row.sourceItemId() != null && !row.sourceItemId().isBlank()) {
            identities.add(sourceProvider + "\u0000" + row.sourceItemId());
        }
        for (CloudPlaceExternalRef ref : row.externalReferences()) {
            String provider = externalProvider(ref.provider());
            if (provider != null && ref.externalId() != null && !ref.externalId().isBlank()) {
                identities.add(provider + "\u0000" + ref.externalId());
            }
        }
        return identities;
    }

    private List<String> diagnostics(
        CloudPlaceSourceRow row,
        Set<String> duplicateIdentities
    ) {
        List<String> diagnostics = new ArrayList<>();
        if (!"ACTIVE".equals(row.status())) {
            diagnostics.add("non_active_source_row");
        }
        if (row.legacyH3Resolution() != null && row.legacyH3Resolution() != 11) {
            diagnostics.add("unexpected_legacy_h3_resolution");
        }
        if (row.externalReferences().size() > 1) {
            diagnostics.add("multiple_external_references_preserved");
        }
        if (hasDuplicateIdentity(row, duplicateIdentities)) {
            diagnostics.add("duplicate_external_identity");
        }
        return List.copyOf(diagnostics);
    }

    private String invalidDetailReason(CloudPlaceSourceRow row) {
        if (!validSchedule(row.openingHours(), false)) {
            return "invalid_opening_hours";
        }
        if (!validSchedule(row.dateOverrides(), true)) {
            return "invalid_date_overrides";
        }
        if (!validCrossMidnight(row)) {
            return "invalid_cross_midnight";
        }
        if (!validVisitMinutes(row)) {
            return "invalid_visit_minutes";
        }
        if (!validTraits(row)) {
            return "invalid_traits";
        }
        return null;
    }

    private boolean validSchedule(JsonNode schedule, boolean requiresDate) {
        if (schedule == null || schedule.isNull()) {
            return true;
        }
        if (!schedule.isArray()) {
            return false;
        }
        for (JsonNode entry : schedule) {
            if (requiresDate && entry.path("closed").asBoolean(false)) {
                if (!nonBlankText(entry.get("date"))
                    || !entry.path("windows").isArray()
                    || entry.path("windows").size() != 0) {
                    return false;
                }
                continue;
            }
            if (!entry.isObject()
                || !nonBlankText(entry.get("opens_at"))
                || !nonBlankText(entry.get("closes_at"))
                || !TIME.matcher(entry.get("opens_at").textValue()).matches()
                || !TIME.matcher(entry.get("closes_at").textValue()).matches()) {
                return false;
            }
            JsonNode key = entry.get(requiresDate ? "date" : "day");
            if (!nonBlankText(key)) {
                return false;
            }
            if (requiresDate) {
                try {
                    LocalDate.parse(key.textValue());
                } catch (DateTimeParseException error) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean validCrossMidnight(CloudPlaceSourceRow row) {
        boolean expected = hasOvernightInterval(row.openingHours())
            || hasOvernightInterval(row.dateOverrides());
        return row.crossMidnight() == null || row.crossMidnight() == expected;
    }

    private boolean hasOvernightInterval(JsonNode schedule) {
        if (schedule == null || schedule.isNull() || !schedule.isArray()) {
            return false;
        }
        for (JsonNode entry : schedule) {
            if (hasOvernightInterval(entry.get("opens_at"), entry.get("closes_at"))) {
                return true;
            }
            JsonNode windows = entry.get("windows");
            if (windows != null && windows.isArray()) {
                for (JsonNode window : windows) {
                    if (hasOvernightInterval(window.get("opens_at"), window.get("closes_at"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasOvernightInterval(JsonNode opensAt, JsonNode closesAt) {
        return nonBlankText(opensAt)
            && nonBlankText(closesAt)
            && opensAt.textValue().compareTo(closesAt.textValue()) > 0;
    }

    private boolean validVisitMinutes(CloudPlaceSourceRow row) {
        if (row.visitMinutes() == null
            && row.visitMinutesSource() == null
            && row.visitMinutesOverride() == null) {
            return true;
        }
        return row.visitMinutes() != null
            && row.visitMinutes() > 0
            && row.visitMinutesSource() != null
            && !row.visitMinutesSource().isBlank()
            && (row.visitMinutesOverride() == null || row.visitMinutesOverride() > 0);
    }

    private boolean validTraits(CloudPlaceSourceRow row) {
        if (row.traits() == null || row.traits().isNull()) {
            return row.traitsSource() == null
                && row.traitsModel() == null
                && row.traitsUpdatedAt() == null;
        }
        Set<String> traitNames = new HashSet<>();
        row.traits().fieldNames().forEachRemaining(traitNames::add);
        if (!row.traits().isObject()
            || row.traits().size() != TRAIT_AXES.size()
            || !traitNames.equals(TRAIT_AXES)
            || row.traitsSource() == null
            || row.traitsSource().isBlank()
            || row.traitsModel() == null
            || row.traitsModel().isBlank()
            || row.traitsUpdatedAt() == null
            || row.traitsUpdatedAt().isBlank()) {
            return false;
        }
        return TRAIT_AXES.stream().allMatch(axis -> row.traits().get(axis).isNumber());
    }

    private boolean nonBlankText(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }

    private String cell(CloudPlaceSourceRow row) {
        return CellIdCalculator.toWire(
            CellIdCalculator.fromCoords(row.latitude(), row.longitude())
        );
    }

    private boolean publicCellEligible(CloudPlaceSourceRow row) {
        if (!"TOUR_API".equals(normalized(row.source()))
            || !TOURISM_CELL_ITEM_TYPES.contains(normalized(row.itemType()))) {
            return false;
        }
        return row.publicCellEligible() == null || Boolean.TRUE.equals(row.publicCellEligible());
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private CoordinateStatus coordinateStatus(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return CoordinateStatus.MISSING;
        }
        if (latitude == null
            || longitude == null
            || !Double.isFinite(latitude)
            || !Double.isFinite(longitude)
            || latitude < -90.0
            || latitude > 90.0
            || longitude < -180.0
            || longitude > 180.0) {
            return CoordinateStatus.INVALID;
        }
        return CoordinateStatus.VALID;
    }

    private boolean isInJeonbuk(double latitude, double longitude) {
        return latitude >= JEONBUK_MIN_LATITUDE
            && latitude <= JEONBUK_MAX_LATITUDE
            && longitude >= JEONBUK_MIN_LONGITUDE
            && longitude <= JEONBUK_MAX_LONGITUDE;
    }

    private boolean allowsPublicReuse(String decision) {
        return decision != null && switch (decision.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVED", "APPROVED_PUBLIC_REUSE", "PUBLIC_REUSE_APPROVED" -> true;
            default -> false;
        };
    }

    private String reuseReason(String decision) {
        if (decision == null || decision.isBlank()) {
            return "unreviewed_reuse_rights";
        }
        return switch (decision.trim().toUpperCase(Locale.ROOT)) {
            case "FORBIDDEN", "DENIED", "REJECTED", "NOT_REUSABLE", "REVOKED" -> "forbidden_reuse_rights";
            default -> "unreviewed_reuse_rights";
        };
    }

    private String sourceProvider(String source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case "KAKAO_LOCAL" -> "kakao";
            case "TOUR_API", "AREA_RESTAURANT" -> "public_data";
            default -> null;
        };
    }

    private String externalProvider(String provider) {
        if (provider == null) {
            return null;
        }
        return switch (provider) {
            case "GOOGLE" -> "google";
            case "KAKAO" -> "kakao";
            case "TOUR_API", "AREA_RESTAURANT" -> "public_data";
            default -> null;
        };
    }

    private record Identity(String provider, String externalId, String reason) {
    }

    private enum CoordinateStatus {
        MISSING("missing_coordinates"),
        INVALID("invalid_coordinates"),
        VALID(null);

        private final String reason;

        CoordinateStatus(String reason) {
            this.reason = reason;
        }
    }
}
