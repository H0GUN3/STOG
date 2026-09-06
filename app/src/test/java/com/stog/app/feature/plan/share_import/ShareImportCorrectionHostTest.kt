package com.stog.app.feature.plan.share_import

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.stog.app.core.database.CandidateOrigin
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.PlaceSearchProvenance
import com.stog.app.ui.theme.STOGTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareImportCorrectionHostTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun providerBackedCorrectionChangesOnlyNameAddressAndOrigin() {
        val provider = PlaceSearchCandidate(
            "external", "provider name", "provider address", 35.8, 127.1, listOf("cafe"),
            rating = 4.7, userRatingCount = 19,
        )
        var state by mutableStateOf(selectedState("provider", CandidateOrigin.EXTRACTED, provider))
        render(state) { state = it }

        correctThroughSemantics("provider", "corrected name", "corrected address")

        composeRule.runOnIdle {
            val corrected = state.candidates.single()
            assertEquals(CandidateOrigin.CORRECTED, corrected.origin)
            assertEquals(CandidateReviewDecision.SELECTED, corrected.decision)
            assertEquals(provider.copy(name = "corrected name", address = "corrected address"), corrected.selectedPlace)
            assertEquals(corrected.selectedPlace, corrected.providerMatches.single())
        }
    }

    @Test
    fun canonicalCorrectionPreservesCanonicalSourceAndCoordinates() {
        val canonical = PlaceSearchCandidate(
            "canonical-external", "canonical name", "canonical address", 36.0, 128.0, listOf("museum"),
            provenance = PlaceSearchProvenance.Canonical(41, "public_data", 73, "public"),
        )
        var state by mutableStateOf(selectedState("canonical", CandidateOrigin.EXTRACTED, canonical))
        render(state) { state = it }

        correctThroughSemantics("canonical", "corrected canonical", "corrected canonical address")

        composeRule.runOnIdle {
            val selected = state.candidates.single().selectedPlace!!
            assertEquals(canonical.copy(name = "corrected canonical", address = "corrected canonical address"), selected)
            assertEquals(PlaceSearchProvenance.Canonical(41, "public_data", 73, "public"), selected.provenance)
            assertEquals(CandidateOrigin.CORRECTED, state.candidates.single().origin)
        }
    }

    @Test
    fun manualCorrectionEditsNameAndAddressWithoutInventingProvider() {
        var state by mutableStateOf(ShareReviewState(
            "import", true, selectedTripId = 4,
            candidates = listOf(ShareReviewCandidate("manual", "old", null, CandidateOrigin.MANUAL)),
        ))
        render(state) { state = it }

        correctThroughSemantics("manual", "manual name", "manual address")

        composeRule.runOnIdle {
            val corrected = state.candidates.single()
            assertEquals("manual name", corrected.title)
            assertEquals("manual address", corrected.address)
            assertEquals(CandidateOrigin.CORRECTED, corrected.origin)
            assertEquals(CandidateReviewDecision.PENDING, corrected.decision)
            assertNull(corrected.selectedPlace)
            assertEquals(ProviderSearchStatus.IDLE, corrected.providerSearchStatus)
        }
    }

    private fun render(state: ShareReviewState, update: (ShareReviewState) -> Unit) {
        composeRule.setContent {
            STOGTheme {
                CorrectCandidateControl(state.candidates.single()) { name, address ->
                    update(state.correct(state.candidates.single().candidateId, name, address))
                }
            }
        }
    }

    private fun correctThroughSemantics(candidateId: String, name: String, address: String) {
        composeRule.onNodeWithTag("share-correct-name:$candidateId").performTextClearance()
        composeRule.onNodeWithTag("share-correct-name:$candidateId").performTextInput(name)
        composeRule.onNodeWithTag("share-correct-address:$candidateId").performTextClearance()
        composeRule.onNodeWithTag("share-correct-address:$candidateId").performTextInput(address)
        composeRule.onNodeWithTag("share-correct-apply:$candidateId").performClick()
    }

    private fun selectedState(
        candidateId: String,
        origin: CandidateOrigin,
        place: PlaceSearchCandidate,
    ) = ShareReviewState(
        "import", true, selectedTripId = 4,
        candidates = listOf(ShareReviewCandidate(
            candidateId = candidateId,
            title = place.name,
            address = place.address,
            origin = origin,
            decision = CandidateReviewDecision.SELECTED,
            providerSearchStatus = ProviderSearchStatus.LOADED,
            providerMatches = listOf(place),
            selectedPlace = place,
        )),
    )
}
