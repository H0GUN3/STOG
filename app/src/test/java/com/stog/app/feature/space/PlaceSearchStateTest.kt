package com.stog.app.feature.space

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchStateTest {
    @Test
    fun guestsCanSearchButOnlySaveActionRequiresLogin() {
        val candidate = canonicalCandidate()
        val search = PlaceSearchState().beginSearch(" 전주 한옥마을 ")
        val searched = search.state.completeSearch(
            search.request!!,
            Result.success(listOf(candidate)),
        )

        val save = searched.requestSave(candidate, accessToken = null)

        assertEquals(listOf(candidate), save.state.candidates)
        assertEquals(listOf("전주 한옥마을"), save.state.recentQueries)
        assertEquals(candidate, save.loginRequiredCandidate)
        assertNull(save.state.pendingCandidate)
    }

    @Test
    fun authenticatedUsersCanSaveCanonicalAndGoogleFallbackCandidates() {
        val canonicalSave = saveSuccessfully(canonicalCandidate())
        val fallbackSave = saveSuccessfully(googleFallbackCandidate())

        assertEquals(setOf("catalog-place"), canonicalSave.savedExternalIds)
        assertEquals(setOf("ChIJfallback"), fallbackSave.savedExternalIds)
        assertNull(canonicalSave.pendingCandidate)
        assertNull(fallbackSave.pendingCandidate)
    }

    @Test
    fun emptyAndFailedSearchesReachRecoverableTerminalStates() {
        val emptyStart = PlaceSearchState().beginSearch("없는 장소")
        val empty = emptyStart.state.completeSearch(emptyStart.request!!, Result.success(emptyList()))
        assertTrue(empty.candidates.isEmpty())
        assertTrue(empty.emptyResult)
        assertNull(empty.failure)

        val networkStart = PlaceSearchState().beginSearch("전주")
        val network = networkStart.state.completeSearch(
            networkStart.request!!,
            Result.failure(IOException("network unavailable")),
        )
        assertFalse(network.searching)
        assertEquals(PlaceSearchFailure.OFFLINE, network.failure)

        val malformedStart = PlaceSearchState().beginSearch("전주")
        val malformed = malformedStart.state.completeSearch(
            malformedStart.request!!,
            Result.failure(IllegalArgumentException("malformed search response")),
        )
        assertFalse(malformed.searching)
        assertEquals(PlaceSearchFailure.UNKNOWN, malformed.failure)
    }

    @Test
    fun staleSearchAndSaveResponsesCannotReplaceNewQueryStateOrSaveWrongCandidate() {
        val firstCandidate = canonicalCandidate()
        val secondCandidate = googleFallbackCandidate()
        val firstSearch = PlaceSearchState().beginSearch("첫 검색")
        val stateAfterQueryChange = firstSearch.state.invalidateForQueryChange()
        val secondSearch = stateAfterQueryChange.beginSearch("두 번째 검색")
        val latest = secondSearch.state.completeSearch(
            secondSearch.request!!,
            Result.success(listOf(secondCandidate)),
        )

        val ignoredStaleSearch = latest.completeSearch(
            firstSearch.request!!,
            Result.success(listOf(firstCandidate)),
        )
        assertEquals(listOf(secondCandidate), ignoredStaleSearch.candidates)

        val selected = latest.requestSave(secondCandidate, accessToken = "token").state
        val saveStart = selected.beginSave(secondCandidate, tripId = 2L)
        val afterAnotherQuery = saveStart.state.invalidateForQueryChange()
        val ignoredStaleSave = afterAnotherQuery.completeSave(
            saveStart.request!!,
            Result.success(addedBasketItem()),
        )
        assertTrue(ignoredStaleSave.savedExternalIds.isEmpty())
        assertNull(ignoredStaleSave.pendingCandidate)
    }

    @Test
    fun expiredAuthenticationDuringSaveRequestsLoginWithoutMarkingCandidateSaved() {
        val candidate = googleFallbackCandidate()
        val selected = PlaceSearchState().requestSave(candidate, accessToken = "expired-token").state
        val saveStart = selected.beginSave(candidate, tripId = 3L)
        val completed = saveStart.state.completeSave(
            saveStart.request!!,
            Result.failure(PlanningRequestException(401, "AUTH_TOKEN_EXPIRED")),
        )

        assertTrue(saveStart.state.isCurrent(saveStart.request!!))
        assertTrue(
            PlanningRequestException(401, "AUTH_TOKEN_EXPIRED")
                .isPlaceSaveAuthenticationFailure(),
        )
        assertTrue(completed.savedExternalIds.isEmpty())
        assertEquals(PlaceSearchFailure.AUTH_EXPIRED, completed.failure)
    }

    @Test
    fun changingTripClearsCandidatesAndRejectsTheStaleTripSaveCompletion() {
        val candidate = canonicalCandidate()
        val selected = PlaceSearchState(candidates = listOf(candidate))
            .requestSave(candidate, accessToken = "token")
            .state
        val firstTripSave = selected.beginSave(candidate, tripId = 10L)

        val changedTrip = firstTripSave.state.selectTrip(11L)
        val ignoredCompletion = changedTrip.completeSave(
            firstTripSave.request!!,
            Result.success(addedBasketItem()),
        )

        assertEquals(11L, changedTrip.selectedTripId)
        assertTrue(changedTrip.candidates.isEmpty())
        assertNull(changedTrip.pendingCandidate)
        assertNull(changedTrip.savingExternalId)
        assertTrue(ignoredCompletion.savedExternalIds.isEmpty())
    }

    private fun saveSuccessfully(candidate: PlaceSearchCandidate): PlaceSearchState {
        val selected = PlaceSearchState().requestSave(candidate, accessToken = "token").state
        val saveStart = selected.beginSave(candidate, tripId = 1L)
        return saveStart.state.completeSave(saveStart.request!!, Result.success(addedBasketItem()))
    }

    private fun canonicalCandidate(): PlaceSearchCandidate = PlaceSearchCandidate(
        externalId = "catalog-place",
        name = "전주 한옥마을",
        address = "전북 전주시",
        latitude = 35.815,
        longitude = 127.153,
        types = listOf("tourist_attraction"),
        provenance = PlaceSearchProvenance.Canonical(11, "public_data", 4, "public"),
    )

    private fun googleFallbackCandidate(): PlaceSearchCandidate = PlaceSearchCandidate(
        externalId = "ChIJfallback",
        name = "Google fallback",
        address = "전북 전주시",
        latitude = 35.816,
        longitude = 127.154,
        types = listOf("cafe"),
    )

    private fun addedBasketItem(): AddedBasketItem = AddedBasketItem(
        id = 1,
        placeId = 11,
        cellId = "8a2a1072b59ffff",
        status = "resolved",
    )
}
