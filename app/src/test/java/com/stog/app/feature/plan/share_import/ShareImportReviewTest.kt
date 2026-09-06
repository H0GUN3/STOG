package com.stog.app.feature.plan.share_import

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.CandidateOrigin
import com.stog.app.core.database.OutboxState
import com.stog.app.core.database.PendingShareImportEntity
import com.stog.app.core.database.PendingShareImportStatus
import com.stog.app.core.database.ShareImportCandidateEntity
import com.stog.app.core.database.ShareImportOutboxTransport
import com.stog.app.core.database.StogDatabase
import com.stog.app.core.database.TransmissionOutcome
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.PlaceSearchProvenance
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareImportReviewTest {
    private lateinit var context: Context
    private lateinit var database: StogDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = StogDatabase.inMemory(context)
        root = File(context.cacheDir, "share-review-${UUID.randomUUID()}").also(File::mkdirs)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun zeroOneManyCorrectionManualTripSwitchAndProviderStatesAreIndependent() {
        val zero = ShareReviewState("zero", intakePersisted = true)
        assertTrue(zero.candidates.isEmpty())
        assertFalse(zero.canSave())

        val one = ShareReviewState("one", true, candidates = listOf(card("one:0", "원본")))
            .selectTrip(1)
            .correct("one:0", "수정 장소")
        val start = one.beginProviderSearch("one:0")
        val loading = start.state
        val request = requireNotNull(start.request)
        assertEquals("수정 장소", request.query)
        assertEquals(ProviderSearchStatus.LOADING, loading.candidates.single().providerSearchStatus)
        val empty = loading.completeProviderSearch(request, Result.success(emptyList()))
        assertEquals(ProviderSearchStatus.EMPTY, empty.candidates.single().providerSearchStatus)
        val failed = loading.completeProviderSearch(request, Result.failure(java.io.IOException()))
        assertEquals(ProviderSearchStatus.ERROR, failed.candidates.single().providerSearchStatus)

        val many = ShareReviewState(
            "many", true, candidates = listOf(card("many:0", "첫째"), card("many:1", "둘째")),
        ).selectTrip(1)
        val manyStart = many.beginProviderSearch("many:0")
        val loaded = manyStart.state
            .completeProviderSearch(requireNotNull(manyStart.request), Result.success(listOf(provider("p1", "첫째"))))
            .select("many:0", provider("p1", "첫째"))
            .reject("many:1")
        assertTrue(loaded.canSave())

        val switched = loaded.selectTrip(2)
        assertEquals(2L, switched.selectedTripId)
        assertTrue(switched.candidates.all { it.decision == CandidateReviewDecision.PENDING })
        assertTrue(switched.candidates.all { it.providerMatches.isEmpty() && it.selectedPlace == null })
        assertFalse(switched.saving)

        val manualImage = zero.addManual("사진 속 수동 장소").selectTrip(3)
        assertEquals(CandidateOrigin.MANUAL, manualImage.candidates.single().origin)
        assertEquals("사진 속 수동 장소", manualImage.beginProviderSearch(manualImage.candidates.single().candidateId).request?.query)
    }

    @Test
    fun tripSwitchInvalidatesDeferredProviderSuccessAndErrorWithoutRepopulatingState() {
        val base = ShareReviewState("race", true, candidates = listOf(card("race:0", "A"))).selectTrip(1)
        val start = base.beginProviderSearch("race:0")
        val current = AtomicReference(start.state)
        val deferred = CompletableFuture<Result<List<PlaceSearchCandidate>>>()
        val applied = deferred.thenApply { result ->
            current.updateAndGet { it.completeProviderSearch(requireNotNull(start.request), result) }
        }

        current.set(current.get().selectTrip(2))
        deferred.complete(Result.success(listOf(provider("late", "late"))))
        val afterLateSuccess = applied.get(2, TimeUnit.SECONDS)
        assertEquals(2L, afterLateSuccess.selectedTripId)
        assertTrue(afterLateSuccess.candidates.single().providerMatches.isEmpty())
        assertEquals(ProviderSearchStatus.IDLE, afterLateSuccess.candidates.single().providerSearchStatus)
        assertFalse(afterLateSuccess.canSave())

        val errorStart = afterLateSuccess.beginProviderSearch("race:0")
        val switchedAgain = errorStart.state.selectTrip(3)
        val afterLateError = switchedAgain.completeProviderSearch(
            requireNotNull(errorStart.request), Result.failure(java.io.IOException("late")),
        )
        assertEquals(ProviderSearchStatus.IDLE, afterLateError.candidates.single().providerSearchStatus)
        assertNull(afterLateError.candidates.single().selectedPlace)
    }

    @Test
    fun providerSearchIsForbiddenBeforeDurableIntakeAndAllRejectedNeedsNoBackendWrite() {
        val premature = ShareReviewState("raw", false, candidates = listOf(card("raw:0", "장소")))
        assertTrue(runCatching { premature.beginProviderSearch("raw:0") }.isFailure)

        seed("rejected", listOf("rejected:0", "rejected:1"))
        val state = ShareReviewState(
            "rejected", true, candidates = listOf(card("rejected:0", "A"), card("rejected:1", "B")),
        ).selectTrip(7).reject("rejected:0").reject("rejected:1")
        var scheduled = 0
        ShareReviewPersistence(database, now = { 10 }, scheduleSync = { _, _ -> scheduled++ })
            .save("account", state)

        assertEquals(1, scheduled)
        assertEquals(PendingShareImportStatus.COMPLETED, database.shareImportDao().pending("rejected")?.status)
        assertNull(database.shareImportDao().nextConfirmed("account"))
    }

    @Test
    fun correctedSelectedCandidatePersistsEditableFieldsAndImmutableCanonicalProvenance() {
        seed("corrected", listOf("corrected:0"))
        val place = PlaceSearchCandidate(
            "canonical-external", "provider name", "provider address", 35.8, 127.1, listOf("museum"),
            provenance = PlaceSearchProvenance.Canonical(41, "public_data", 73, "public"),
        )
        val state = ShareReviewState(
            "corrected", true, selectedTripId = 7,
            candidates = listOf(card("corrected:0", "original").copy(
                decision = CandidateReviewDecision.SELECTED,
                providerSearchStatus = ProviderSearchStatus.LOADED,
                providerMatches = listOf(place),
                selectedPlace = place,
            )),
        ).correct("corrected:0", "corrected name", "corrected address")

        ShareReviewPersistence(database, scheduleSync = { _, _ -> }, now = { 10 }).save("account", state)

        val resolved = database.shareImportDao().candidates("corrected")
            .single { it.candidateId == "corrected:0:resolved" }
        assertEquals(CandidateOrigin.CORRECTED, resolved.origin)
        assertEquals("corrected name", resolved.title)
        assertEquals("corrected address", resolved.address)
        assertEquals("canonical", resolved.provider)
        assertEquals("canonical-external", resolved.externalId)
        assertEquals(41L, resolved.canonicalPlaceId)
        assertEquals(73L, resolved.canonicalSourceId)
        assertEquals(35.8, resolved.latitude)
        assertEquals(127.1, resolved.longitude)
        assertEquals("attraction", resolved.category)
    }

    @Test
    fun terminalConflictIsRetainedWithoutRawCleanupWhileServerFailureRetries() {
        seed("terminal", listOf("terminal:0"))
        val place = provider("google-terminal", "terminal")
        val terminalBase = ShareReviewState(
            "terminal", true, candidates = listOf(card("terminal:0", "terminal")),
        ).selectTrip(12)
        val terminalStart = terminalBase.beginProviderSearch("terminal:0")
        val state = terminalStart.state
            .completeProviderSearch(requireNotNull(terminalStart.request), Result.success(listOf(place)))
            .select("terminal:0", place)
        ShareReviewPersistence(database, scheduleSync = { _, _ -> }).save("account", state)

        assertFalse(ShareImportSyncRunner(
            database,
            root,
            ShareImportOutboxTransport { _, _ -> TransmissionOutcome.HttpFailure(409) },
        ).run("account"))

        val confirmed = database.shareImportDao().decisions("terminal")
            .single { it.decision == com.stog.app.core.database.CandidateDecisionState.CONFIRMED }
        assertEquals(OutboxState.TERMINAL, confirmed.syncState)
        assertEquals(com.stog.app.core.database.RetryClass.TERMINAL_CLIENT, confirmed.retryClass)
        assertEquals(PendingShareImportStatus.CLASSIFIED, database.shareImportDao().pending("terminal")?.status)
        assertTrue(File(root, "terminal").exists())
    }

    @Test
    fun partialAcknowledgementRetainsRawAndCommitBeforeCleanupRecoversWithoutDuplicateWrite() {
        seed("partial", listOf("partial:0", "partial:1"))
        val firstPlace = provider("google-1", "첫째")
        val secondPlace = provider("google-2", "둘째")
        val partialBase = ShareReviewState(
            "partial", true, candidates = listOf(card("partial:0", "첫째"), card("partial:1", "둘째")),
        ).selectTrip(11)
        val firstStart = partialBase.beginProviderSearch("partial:0")
        val firstSelected = firstStart.state
            .completeProviderSearch(requireNotNull(firstStart.request), Result.success(listOf(firstPlace)))
            .select("partial:0", firstPlace)
        val secondStart = firstSelected.beginProviderSearch("partial:1")
        val state = secondStart.state
            .completeProviderSearch(requireNotNull(secondStart.request), Result.success(listOf(secondPlace)))
            .select("partial:1", secondPlace)
        ShareReviewPersistence(database, scheduleSync = { _, _ -> }, now = { 20 }).save("account", state)

        val sent = mutableListOf<String>()
        val firstRun = ShareImportSyncRunner(database, root, ShareImportOutboxTransport { decision, _ ->
            sent += decision.candidateId
            if (sent.size == 1) TransmissionOutcome.Acknowledged else TransmissionOutcome.HttpFailure(503)
        })
        assertTrue(firstRun.run("account"))
        assertTrue(File(root, "partial").exists())
        assertEquals(PendingShareImportStatus.CLASSIFIED, database.shareImportDao().pending("partial")?.status)
        assertEquals(1, database.shareImportDao().decisions("partial").count { it.syncState == OutboxState.ACKNOWLEDGED })

        val replayed = mutableListOf<String>()
        val commitOnly = com.stog.app.core.database.TypedOutboxProcessor(
            database,
            com.stog.app.core.database.VisitOutboxTransport { error("unused") },
            ShareImportOutboxTransport { decision, _ -> replayed += decision.candidateId; TransmissionOutcome.Acknowledged },
        )
        assertFalse(commitOnly.processShareImports("account"))
        assertEquals(1, replayed.size)
        assertEquals(PendingShareImportStatus.COMPLETED, database.shareImportDao().pending("partial")?.status)
        assertTrue(File(root, "partial").exists())

        var restartWrites = 0
        ShareImportSyncRunner(database, root, ShareImportOutboxTransport { _, _ ->
            restartWrites++
            TransmissionOutcome.Acknowledged
        }).run("account")
        assertEquals(0, restartWrites)
        assertFalse(File(root, "partial").exists())
        assertNull(database.shareImportDao().pending("partial"))
    }

    private fun seed(importId: String, candidateIds: List<String>) {
        database.accountOwnershipDao().insert(AccountOwnershipEntity("account", "user", 1))
        database.shareImportDao().insertPending(
            PendingShareImportEntity(importId, null, importId, "secret raw", null, "text/plain", "UNKNOWN", createdAt = 1),
        )
        File(root, importId).also { it.mkdirs(); File(it, "raw").writeText("secret raw") }
        database.shareImportDao().persistCandidates(importId, candidateIds.mapIndexed { index, id ->
            ShareImportCandidateEntity(id, importId, CandidateOrigin.EXTRACTED, "candidate-$index", null, null, index)
        })
    }

    private fun card(id: String, title: String) = ShareReviewCandidate(id, title)

    private fun provider(id: String, name: String) = PlaceSearchCandidate(
        externalId = id,
        name = name,
        address = "주소",
        latitude = 35.8,
        longitude = 127.1,
        types = listOf("cafe"),
        provenance = PlaceSearchProvenance.GoogleFallback,
    )
}
