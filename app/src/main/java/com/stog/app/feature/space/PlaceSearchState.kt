package com.stog.app.feature.space

import java.io.IOException

internal enum class PlaceSearchFailure {
    OFFLINE,
    AUTH_EXPIRED,
    UNKNOWN,
}

internal data class PlaceSearchState(
    val candidates: List<PlaceSearchCandidate> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val pendingCandidate: PlaceSearchCandidate? = null,
    val savedExternalIds: Set<String> = emptySet(),
    val searching: Boolean = false,
    val savingExternalId: String? = null,
    val selectedTripId: Long? = null,
    val message: String? = null,
    val failure: PlaceSearchFailure? = null,
    val emptyResult: Boolean = false,
    private val searchRequestVersion: Long = 0L,
    private val saveRequestVersion: Long = 0L,
) {
    fun resumePendingCandidate(candidate: PlaceSearchCandidate): PlaceSearchState = copy(
        candidates = if (candidates.any { it.sameSearchResult(candidate) }) {
            candidates
        } else {
            listOf(candidate) + candidates
        },
        pendingCandidate = candidate,
    )

    fun invalidateForQueryChange(): PlaceSearchState = copy(
        pendingCandidate = null,
        savingExternalId = null,
        selectedTripId = null,
        message = null,
        failure = null,
        emptyResult = false,
        searchRequestVersion = searchRequestVersion + 1,
        saveRequestVersion = saveRequestVersion + 1,
    )

    fun beginSearch(term: String): PlaceSearchStart {
        val normalizedTerm = term.trim()
        val version = searchRequestVersion + 1
        if (normalizedTerm.isBlank()) {
            return PlaceSearchStart(
                state = copy(
                    searching = false,
                    message = "검색어를 입력해주세요.",
                    failure = null,
                    emptyResult = false,
                    searchRequestVersion = version,
                ),
                request = null,
            )
        }
        return PlaceSearchStart(
            state = copy(
                searching = true,
                message = null,
                failure = null,
                emptyResult = false,
                searchRequestVersion = version,
            ),
            request = PlaceSearchRequest(version, normalizedTerm),
        )
    }

    fun isCurrent(request: PlaceSearchRequest): Boolean =
        request.version == searchRequestVersion

    fun completeSearch(
        request: PlaceSearchRequest,
        result: Result<List<PlaceSearchCandidate>>,
    ): PlaceSearchState {
        if (!isCurrent(request)) return this
        return result.fold(
            onSuccess = { results ->
                copy(
                    candidates = results,
                    recentQueries = (listOf(request.term) + recentQueries).distinct().take(4),
                    searching = false,
                    message = if (results.isEmpty()) "검색 결과가 없어요." else null,
                    failure = null,
                    emptyResult = results.isEmpty(),
                )
            },
            onFailure = { error ->
                copy(
                    searching = false,
                    message = if (error is IOException) {
                        "장소 검색에 실패했어요."
                    } else {
                        "장소 검색을 시작하지 못했어요."
                    },
                    failure = if (error is IOException) {
                        PlaceSearchFailure.OFFLINE
                    } else {
                        PlaceSearchFailure.UNKNOWN
                    },
                    emptyResult = false,
                )
            },
        )
    }

    fun requestSave(
        candidate: PlaceSearchCandidate,
        accessToken: String?,
    ): PlaceSaveSelection = when {
        !candidate.hasValidCoordinates() -> PlaceSaveSelection(
            state = copy(message = "좌표가 있는 장소만 담을 수 있어요."),
        )

        accessToken.isNullOrBlank() -> PlaceSaveSelection(
            state = this,
            loginRequiredCandidate = candidate,
        )

        else -> PlaceSaveSelection(
            state = copy(
                pendingCandidate = candidate,
                message = null,
                failure = null,
            ),
        )
    }

    fun selectTrip(tripId: Long): PlaceSearchState {
        require(tripId > 0L)
        if (selectedTripId == null || selectedTripId == tripId) {
            return copy(selectedTripId = tripId)
        }
        return copy(
            candidates = emptyList(),
            pendingCandidate = null,
            savingExternalId = null,
            selectedTripId = tripId,
            message = null,
            failure = null,
            emptyResult = false,
            searchRequestVersion = searchRequestVersion + 1,
            saveRequestVersion = saveRequestVersion + 1,
        )
    }

    fun beginSave(candidate: PlaceSearchCandidate, tripId: Long): PlaceSaveStart {
        val selected = selectTrip(tripId)
        if (selected.pendingCandidate != candidate) return PlaceSaveStart(selected, null)
        val version = selected.saveRequestVersion + 1
        return PlaceSaveStart(
            state = selected.copy(
                savingExternalId = candidate.externalId,
                message = null,
                failure = null,
                saveRequestVersion = version,
            ),
            request = PlaceSaveRequest(version, tripId, candidate),
        )
    }

    fun isCurrent(request: PlaceSaveRequest): Boolean =
        request.version == saveRequestVersion

    fun completeSave(
        request: PlaceSaveRequest,
        result: Result<AddedBasketItem>,
    ): PlaceSearchState {
        if (!isCurrent(request)) return this
        return result.fold(
            onSuccess = {
                copy(
                    pendingCandidate = null,
                    savedExternalIds = savedExternalIds + request.candidate.externalId,
                    savingExternalId = null,
                    selectedTripId = null,
                    message = "${request.candidate.name}을(를) 담았어요.",
                    failure = null,
                )
            },
            onFailure = { error ->
                copy(
                    savingExternalId = null,
                    message = placeSaveFailureMessage(error),
                    failure = if (error.isPlaceSaveAuthenticationFailure()) {
                        PlaceSearchFailure.AUTH_EXPIRED
                    } else {
                        PlaceSearchFailure.UNKNOWN
                    },
                )
            },
        )
    }
}

internal data class PlaceSearchRequest(
    val version: Long,
    val term: String,
)

internal data class PlaceSearchStart(
    val state: PlaceSearchState,
    val request: PlaceSearchRequest?,
)

internal data class PlaceSaveSelection(
    val state: PlaceSearchState,
    val loginRequiredCandidate: PlaceSearchCandidate? = null,
)

internal data class PlaceSaveRequest(
    val version: Long,
    val tripId: Long,
    val candidate: PlaceSearchCandidate,
)

internal data class PlaceSaveStart(
    val state: PlaceSearchState,
    val request: PlaceSaveRequest?,
)

internal fun placeSaveFailureMessage(error: Throwable): String =
    if (error is PlanningRequestException && error.statusCode == 401) {
        "로그인이 만료되었어요. 다시 로그인해주세요."
    } else {
        "장소를 담지 못했어요."
    }

internal fun Throwable.isPlaceSaveAuthenticationFailure(): Boolean =
    this is PlanningRequestException && statusCode == 401

private fun PlaceSearchCandidate.sameSearchResult(other: PlaceSearchCandidate): Boolean =
    externalId == other.externalId && provenance == other.provenance
