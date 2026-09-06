package com.stog.backend.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CloudPlaceReconciler {
    public CloudPlaceReconciliationResult reconcile(List<CloudPlaceRow> input) {
        Map<String, List<CloudPlaceRow>> grouped = new LinkedHashMap<>();
        long orphanReferences = 0;
        for (CloudPlaceRow row : input) {
            if (row.travelItemId() == null) {
                if (row.externalProvider() != null || row.externalId() != null) {
                    orphanReferences++;
                }
                continue;
            }
            grouped.computeIfAbsent(row.travelItemId(), ignored -> new ArrayList<>())
                .add(row);
        }

        List<CloudPlaceSourceRow> rows = new ArrayList<>();
        long duplicateReferences = 0;
        for (List<CloudPlaceRow> group : grouped.values()) {
            group.sort(Comparator
                .comparing(CloudPlaceRow::externalProvider, Comparator.nullsLast(String::compareTo))
                .thenComparing(
                    CloudPlaceRow::externalId,
                    Comparator.nullsLast(String::compareTo)
                ));
            CloudPlaceRow base = group.get(0);
            Map<String, CloudPlaceExternalRef> refs = new LinkedHashMap<>();
            for (CloudPlaceRow row : group) {
                if (row.externalProvider() == null && row.externalId() == null) {
                    continue;
                }
                String key = String.valueOf(row.externalProvider())
                    + "\u0000"
                    + String.valueOf(row.externalId());
                if (refs.putIfAbsent(
                    key,
                    new CloudPlaceExternalRef(row.externalProvider(), row.externalId())
                ) != null) {
                    duplicateReferences++;
                }
            }
            rows.add(CloudPlaceSourceRow.from(base, refs.values().stream().toList()));
        }
        rows.sort(Comparator.comparing(
            CloudPlaceSourceRow::travelItemId,
            Comparator.nullsLast(String::compareTo)
        ));
        return new CloudPlaceReconciliationResult(
            input.size(),
            rows,
            duplicateReferences,
            orphanReferences
        );
    }
}
