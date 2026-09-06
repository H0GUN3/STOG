package com.stog.app.feature.space

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items

internal fun LazyListScope.placeResultItems(
    candidates: List<PlaceSearchCandidate>,
    baseUrl: String,
    savedExternalIds: Set<String>,
    savingExternalId: String?,
    onSelect: (PlaceSearchCandidate) -> Unit,
    onSave: (PlaceSearchCandidate) -> Unit,
) {
    items(candidates, key = { it.externalId }) { candidate ->
        PlaceCandidateCard(
            candidate = candidate,
            baseUrl = baseUrl,
            saved = candidate.externalId in savedExternalIds,
            saving = candidate.externalId == savingExternalId,
            onSelect = { onSelect(candidate) },
            onSave = { onSave(candidate) },
        )
    }
}
