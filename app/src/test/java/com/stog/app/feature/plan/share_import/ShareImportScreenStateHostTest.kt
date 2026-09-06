package com.stog.app.feature.plan.share_import

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.stog.app.core.database.CandidateDecisionState
import com.stog.app.core.database.CandidateOrigin
import com.stog.app.core.database.OutboxState
import com.stog.app.core.database.PendingShareImportEntity
import com.stog.app.core.database.RetryClass
import com.stog.app.core.database.ShareImportCandidateEntity
import com.stog.app.core.database.ShareImportDecisionEntity
import com.stog.app.core.database.StogDatabase
import com.stog.app.feature.space.TripSummary
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogStatePanelTag
import com.stog.app.ui.theme.STOGTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareImportScreenStateHostTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var database: StogDatabase

    @Before fun setUp() {
        database = StogDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() {
        database.close()
    }

    @Test
    fun persistedPendingWriteRendersHonestQueuedStateOnProductionScreen() {
        renderPersisted(OutboxState.PENDING, RetryClass.NONE)
        assertState(StogSurfaceState.CONTENT)
    }

    @Test
    fun persistedServerRetryRemainsQueuedInsteadOfClaimingOffline() {
        renderPersisted(OutboxState.RETRY, RetryClass.SERVER)
        assertState(StogSurfaceState.CONTENT)
        composeRule.onNodeWithTag(stogStatePanelTag(StogSurfaceState.OFFLINE)).assertDoesNotExist()
    }

    @Test
    fun persistedNetworkRetryIsTheOnlyOfflineProductionStateAndTargetsAreLargeEnough() {
        renderPersisted(OutboxState.RETRY, RetryClass.NETWORK)
        assertState(StogSurfaceState.OFFLINE)
        val actions = composeRule.onAllNodes(hasClickAction())
        val count = actions.fetchSemanticsNodes().size
        check(count > 0)
        repeat(count) { index ->
            actions[index]
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    private fun renderPersisted(syncState: OutboxState, retryClass: RetryClass) {
        val id = "screen-${syncState.name.lowercase()}"
        database.shareImportDao().insertPending(
            PendingShareImportEntity(id, null, id, "raw", null, "text/plain", "UNKNOWN", createdAt = 1),
        )
        database.shareImportDao().persistCandidates(
            id,
            listOf(ShareImportCandidateEntity("$id:0", id, CandidateOrigin.EXTRACTED, "place", null, null, 0)),
        )
        database.shareImportDao().insertDecision(
            ShareImportDecisionEntity(
                candidateId = "$id:0",
                importId = id,
                decision = CandidateDecisionState.CONFIRMED,
                tripId = 7,
                clientItemId = "client-$id",
                payloadFingerprint = "a".repeat(64),
                syncState = syncState,
                retryClass = retryClass,
                decidedAt = 2,
            ),
        )
        val local = LocalShareImport(id, File("."), emptyList(), emptyList())
        val normalized = NormalizedShareImport(
            ShareSource.UNKNOWN,
            "place",
            null,
            "https://example.test/place",
            listOf(PlaceMention("place", null)),
            emptyList(),
        )
        composeRule.setContent {
            STOGTheme {
                ShareImportScreen(
                    state = ShareImportUiState.Saved(normalized, local),
                    onReviewSaved = { _, _, _ -> },
                    baseUrl = "https://unused.test",
                    accessToken = "token",
                    accountId = "account",
                    databaseProvider = { database },
                    initialTrips = listOf(TripSummary(7, "trip", "tour", "dormant", "private", null, null)),
                    scheduleSync = { _, _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertState(expected: StogSurfaceState) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(stogStatePanelTag(expected))
                .fetchSemanticsNodes()
                .size == 1
        }
        val config = composeRule.onNodeWithTag(stogStatePanelTag(expected)).fetchSemanticsNode().config
        assertEquals(expected.machineName, config.getOrNull(SemanticsProperties.StateDescription))
    }
}
