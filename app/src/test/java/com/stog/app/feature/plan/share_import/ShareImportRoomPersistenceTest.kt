package com.stog.app.feature.plan.share_import

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stog.app.core.database.CandidateDecisionState
import com.stog.app.core.database.CandidateOrigin
import com.stog.app.core.database.PendingShareImportEntity
import com.stog.app.core.database.PendingShareImportStatus
import com.stog.app.core.database.ShareImportCandidateEntity
import com.stog.app.core.database.ShareImportDecisionEntity
import com.stog.app.core.database.StogDatabase
import com.stog.app.feature.space.PlaceSearchCandidate
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareImportRoomPersistenceTest {
    @Test
    fun reviewSaveRollsBackEarlierMutationsWhenReplayIntentConflicts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = StogDatabase.inMemory(context)
        val importId = "conflict-${UUID.randomUUID()}"
        val dao = database.shareImportDao()
        dao.insertPending(pending(importId))
        dao.insertCandidate(
            ShareImportCandidateEntity(
                candidateId = "first",
                importId = importId,
                origin = CandidateOrigin.EXTRACTED,
                title = "First",
                address = null,
                originalUrl = null,
                candidateOrder = 0,
            ),
        )
        dao.insertCandidate(
            ShareImportCandidateEntity(
                candidateId = "second",
                importId = importId,
                origin = CandidateOrigin.EXTRACTED,
                title = "Second",
                address = null,
                originalUrl = null,
                candidateOrder = 1,
            ),
        )
        dao.insertDecision(
            ShareImportDecisionEntity(
                candidateId = "second",
                importId = importId,
                decision = CandidateDecisionState.CONFIRMED,
                tripId = 9,
                clientItemId = "existing",
                payloadFingerprint = "existing",
                decidedAt = 1,
            ),
        )
        val state = ShareReviewState(
            importId = importId,
            intakePersisted = true,
            selectedTripId = 9,
            candidates = listOf(
                ShareReviewCandidate("first", "First", decision = CandidateReviewDecision.REJECTED),
                ShareReviewCandidate("second", "Second", decision = CandidateReviewDecision.REJECTED),
            ),
        )

        val failure = runCatching {
            ShareReviewPersistence(database, { _, _ -> }, now = { 2L }).save("account", state)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(null, dao.pending(importId)?.accountId)
        assertEquals(listOf("second"), dao.decisions(importId).map { it.candidateId })
        database.close()
    }

    @Test
    fun reviewSaveIsAtomicAndRestartIdempotent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = StogDatabase.inMemory(context)
        val importId = "review-${UUID.randomUUID()}"
        database.shareImportDao().insertPending(pending(importId))
        val state = ShareReviewState(
            importId = importId,
            intakePersisted = true,
            selectedTripId = 9,
            candidates = listOf(
                ShareReviewCandidate(
                    candidateId = "selected",
                    title = "Selected",
                    decision = CandidateReviewDecision.SELECTED,
                    selectedPlace = PlaceSearchCandidate(
                        externalId = "selected-place",
                        name = "Selected",
                        address = "address",
                        latitude = 35.9,
                        longitude = 127.1,
                    ),
                ),
                ShareReviewCandidate(
                    candidateId = "rejected",
                    title = "Rejected",
                    decision = CandidateReviewDecision.REJECTED,
                ),
            ),
        )
        var scheduled = 0
        val persistence = ShareReviewPersistence(
            database = database,
            scheduleSync = { _, _ -> scheduled++ },
            now = { 1L },
        )

        offMain {
            persistence.save("account", state)
            persistence.save("account", state)
        }

        val candidates = database.shareImportDao().candidates(importId)
        val decisions = database.shareImportDao().decisions(importId)
        assertEquals(2, candidates.size)
        assertEquals(2, decisions.size)
        assertEquals(
            setOf(CandidateDecisionState.CONFIRMED, CandidateDecisionState.REJECTED),
            decisions.map { it.decision }.toSet(),
        )
        assertEquals(2, scheduled)
        database.close()
    }

    @Test
    fun androidIntakeUsesNoBackupRawStorageAndPersistsRawBeforeCandidates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = ShareImportPayload(
            action = ShareImportActions.SEND,
            mimeType = "text/plain",
            texts = listOf("[네이버지도] 전주 한옥마을", "전주시 완산구 기린대로"),
            htmlText = null,
            dataUri = null,
            streamUris = emptyList(),
            clipDataUris = emptyList(),
        )
        val normalized = ShareImportNormalizer.normalize(payload)

        val stored = offMain { ShareImportFileStore(context).save(payload, normalized) }
        val pending = offMain { StogDatabase.get(context).shareImportDao().pending(stored.local.id) }
        val candidates = offMain { StogDatabase.get(context).shareImportDao().candidates(stored.local.id) }

        assertTrue(stored.local.directory.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertEquals(PendingShareImportStatus.CLASSIFIED, pending?.status)
        assertEquals(listOf("전주 한옥마을"), candidates.map { it.title })
        stored.local.directory.deleteRecursively()
    }

    @Test
    fun candidatePersistenceProgrammerFailurePropagatesAndRetainsCommittedRawForRecovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "partial-${UUID.randomUUID()}")
        var rawPersisted = false
        val payload = ShareImportPayload(
            action = ShareImportActions.SEND,
            mimeType = "text/plain",
            texts = listOf("전주 한옥마을"),
            htmlText = null,
            dataUri = null,
            streamUris = emptyList(),
            clipDataUris = emptyList(),
        )
        val programmerError = IllegalStateException("simulated process boundary failure")
        val failingPersistence = object : ShareImportPersistence {
            override fun persistRaw(importId: String, payload: ShareImportPayload, normalized: NormalizedShareImport) {
                rawPersisted = true
            }

            override fun persistCandidates(importId: String, normalized: NormalizedShareImport) {
                throw programmerError
            }
        }
        val failingStore = ShareImportFileStore(
            rootDirectory = root,
            contentSource = object : ShareImportContentSource {
                override fun mimeType(uri: String): String? = null
                override fun open(uri: String): InputStream? = null
            },
            persistence = failingPersistence,
        )
        val failure = runCatching { failingStore.save(payload, ShareImportNormalizer.normalize(payload)) }.exceptionOrNull()

        assertSame(programmerError, failure)
        assertTrue(rawPersisted)
        assertEquals(1, root.listFiles().orEmpty().count { it.isDirectory && !it.name.startsWith(".staging-") })
        assertEquals(0, root.listFiles().orEmpty().count { it.name.startsWith(".staging-") })
        root.deleteRecursively()
    }

    @Test
    fun programmerFailureBeforeRawDatabaseCommitPropagatesAndCleansIntakeFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "programmer-${UUID.randomUUID()}")
        val programmerError = IllegalArgumentException("programmer bug")
        val payload = textPayload("전주 객사")
        val store = ShareImportFileStore(
            root,
            EmptyContentSource,
            persistence = object : ShareImportPersistence {
                override fun persistRaw(importId: String, payload: ShareImportPayload, normalized: NormalizedShareImport) {
                    throw programmerError
                }

                override fun persistCandidates(importId: String, normalized: NormalizedShareImport) = Unit
            },
        )

        val failure = runCatching { store.save(payload, ShareImportNormalizer.normalize(payload)) }.exceptionOrNull()

        assertSame(programmerError, failure)
        assertEquals(0, root.listFiles().orEmpty().size)
        root.deleteRecursively()
    }

    @Test
    fun restartReconciliationCoversEveryRawAndDatabaseCrashBoundaryIdempotently() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reconcile-${UUID.randomUUID()}").also(File::mkdirs)
        val database = StogDatabase.inMemory(context)
        val dao = database.shareImportDao()
        val rawOnly = saveRaw(root, "원본만 저장")
        val rawCommitted = saveRaw(root, "원본 DB 저장")
        val candidateFailed = saveRaw(root, "후보 transaction 실패")
        val partial = saveRaw(root, "부분 파일")
        File(partial.local.directory, "attachment.partial").writeText("partial")
        File(root, "malformed").also { directory ->
            directory.mkdirs()
            File(directory, "metadata.properties").writeText("status=SAVED\nsource=INVALID\nraw_text=x")
        }
        File(root, ".staging-abandoned").also { it.mkdirs(); File(it, "metadata.properties").writeText("partial") }

        dao.insertPending(pending(rawCommitted.local.id))
        dao.insertPending(pending(candidateFailed.local.id))
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO share_import_candidates
               (candidate_id,import_id,origin,title,address,original_url,candidate_order)
               VALUES ('interrupted-candidate','${candidateFailed.local.id}','EXTRACTED','x',NULL,NULL,0)""".trimIndent(),
        )
        dao.insertPending(pending(partial.local.id))
        dao.insertPending(pending("missing"))
        dao.insertPending(pending("staging-row", ".staging-abandoned"))

        val recovery = ShareImportRecovery(root, dao)
        val firstFailures = recovery.recover().toSet()
        recovery.recover()

        assertEquals(PendingShareImportStatus.CLASSIFIED, dao.pending(rawOnly.local.id)?.status)
        assertEquals(PendingShareImportStatus.CLASSIFIED, dao.pending(rawCommitted.local.id)?.status)
        assertEquals(1, dao.candidates(rawOnly.local.id).size)
        assertEquals(1, dao.candidates(rawCommitted.local.id).size)
        assertEquals(PendingShareImportStatus.FAILED, dao.pending(candidateFailed.local.id)?.status)
        assertEquals(PendingShareImportStatus.FAILED, dao.pending("malformed")?.status)
        assertEquals(PendingShareImportStatus.FAILED, dao.pending("missing")?.status)
        assertEquals(PendingShareImportStatus.FAILED, dao.pending(partial.local.id)?.status)
        assertEquals(PendingShareImportStatus.FAILED, dao.pending("staging-row")?.status)
        assertTrue(candidateFailed.local.id in firstFailures)
        assertTrue("malformed" in firstFailures)
        assertTrue("missing" in firstFailures)
        assertTrue(partial.local.id in firstFailures)
        assertTrue("staging-row" in firstFailures)
        assertFalse(File(root, ".staging-abandoned").exists())
        assertEquals(1, dao.candidates(rawOnly.local.id).size)
        assertEquals(1, dao.candidates(rawCommitted.local.id).size)

        database.close()
        root.deleteRecursively()
    }

    private fun saveRaw(root: File, title: String): StoredShareImport {
        val payload = textPayload(title)
        return ShareImportFileStore(root, EmptyContentSource).save(payload, ShareImportNormalizer.normalize(payload))
    }

    private fun pending(importId: String, directory: String = importId) = PendingShareImportEntity(
        importId = importId,
        accountId = null,
        rawDirectoryName = directory,
        rawText = "raw",
        rawHtml = null,
        mimeType = "text/plain",
        source = ShareSource.UNKNOWN.name,
        createdAt = 1,
    )

    private fun textPayload(title: String) = ShareImportPayload(
        action = ShareImportActions.SEND,
        mimeType = "text/plain",
        texts = listOf(title),
        htmlText = null,
        dataUri = null,
        streamUris = emptyList(),
        clipDataUris = emptyList(),
    )

    private object EmptyContentSource : ShareImportContentSource {
        override fun mimeType(uri: String): String? = null
        override fun open(uri: String): InputStream? = null
    }

    private fun <T> offMain(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { block() }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
