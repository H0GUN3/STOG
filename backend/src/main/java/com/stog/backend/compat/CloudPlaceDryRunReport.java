package com.stog.backend.compat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record CloudPlaceDryRunReport(
    int rowCount,
    long missingCoordinateCount,
    Map<String, Long> sourceCounts,
    Map<Integer, Long> legacyResolutionCounts,
    Map<String, Long> classificationCounts,
    long externalReferenceCount,
    long duplicateReferenceCount,
    long orphanReferenceCount
) {
    public CloudPlaceDryRunReport {
        sourceCounts = Map.copyOf(sourceCounts);
        legacyResolutionCounts = Map.copyOf(legacyResolutionCounts);
        classificationCounts = Map.copyOf(classificationCounts);
    }

    public static CloudPlaceDryRunReport from(List<CloudPlaceMapping> mappings) {
        long missingCoordinates = 0;
        Map<String, Long> sources = new TreeMap<>();
        Map<Integer, Long> resolutions = new TreeMap<>();
        for (CloudPlaceMapping mapping : mappings) {
            if (mapping.candidate().latitude() == null
                || mapping.candidate().longitude() == null) {
                missingCoordinates++;
            }
            sources.merge(mapping.source(), 1L, Long::sum);
            Integer resolution = mapping.legacyH3Resolution();
            if (resolution != null) {
                resolutions.merge(resolution, 1L, Long::sum);
            }
        }
        return new CloudPlaceDryRunReport(
            mappings.size(),
            missingCoordinates,
            sources,
            resolutions,
            Map.of(),
            0,
            0,
            0
        );
    }

    public static CloudPlaceDryRunReport fromNormalizations(
        List<CloudPlaceNormalization> normalizations
    ) {
        long missingCoordinates = 0;
        long externalReferences = 0;
        long duplicateReferences = 0;
        Map<String, Long> sources = new TreeMap<>();
        Map<Integer, Long> resolutions = new TreeMap<>();
        Map<String, Long> classifications = new TreeMap<>();
        for (CloudPlaceNormalization normalization : normalizations) {
            CloudPlaceSourceRow row = normalization.sourceRow();
            if (row.latitude() == null || row.longitude() == null) {
                missingCoordinates++;
            }
            sources.merge(row.source(), 1L, Long::sum);
            Integer resolution = row.legacyH3Resolution();
            if (resolution != null) {
                resolutions.merge(resolution, 1L, Long::sum);
            }
            classifications.merge(normalization.classification(), 1L, Long::sum);
            List<CloudPlaceExternalRef> refs = row.externalReferences();
            externalReferences += refs.size();
            duplicateReferences += refs.size() - refs.stream().distinct().count();
        }
        return new CloudPlaceDryRunReport(
            normalizations.size(),
            missingCoordinates,
            sources,
            resolutions,
            classifications,
            externalReferences,
            duplicateReferences,
            0
        );
    }

    public CloudPlaceDryRunReport withReferenceDiagnostics(
        long orphanReferences,
        long duplicateReferences
    ) {
        return new CloudPlaceDryRunReport(
            rowCount,
            missingCoordinateCount,
            sourceCounts,
            legacyResolutionCounts,
            classificationCounts,
            externalReferenceCount,
            duplicateReferences,
            orphanReferences
        );
    }
}
