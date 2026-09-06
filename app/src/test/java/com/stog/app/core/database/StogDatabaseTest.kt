package com.stog.app.core.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.stog.app.feature.plan.share_import.ShareImportRecovery
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StogDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: StogDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = StogDatabase.inMemory(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observationMustCommitBeforeTransactionalVisitClassification() {
        insertAccount("account-a", "user-a")
        val visit = visit("missing", "account-a", "trip-a", "user-a", "client-1", 1)

        val failure = runCatching { database.visitOutboxDao().enqueue(visit) }.exceptionOrNull()
        assertNotNull(failure)
        assertNull(database.visitOutboxDao().next("account-a", "trip-a"))

        database.locationObservationDao().insert(observation("observation-1", "account-a", "trip-a", "user-a", 1))
        val id = database.visitOutboxDao().enqueue(visit.copy(observationId = "observation-1"))

        assertTrue(id > 0)
        assertEquals(
            LocationClassificationState.CLASSIFIED,
            database.locationObservationDao().find("observation-1")?.classificationState,
        )
    }

    @Test
    fun enqueueRollsBackWhenObservationOwnershipDoesNotMatch() {
        insertAccount("account-a", "user-a")
        database.locationObservationDao().insert(observation("observation-1", "account-a", "trip-a", "user-a", 1))

        val failure = runCatching {
            database.visitOutboxDao().enqueue(
                visit("observation-1", "account-a", "trip-other", "user-a", "client-1", 1),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertNull(database.visitOutboxDao().next("account-a", "trip-a"))
        assertEquals(
            LocationClassificationState.PENDING,
            database.locationObservationDao().find("observation-1")?.classificationState,
        )
    }

    @Test
    fun immutableVisitKeyAndPayloadCannotBeReusedOrUpdated() {
        insertAccount("account-a", "user-a")
        enqueue("account-a", "trip-a", "user-a", "observation-1", "client-1", 1)
        database.locationObservationDao().insert(observation("observation-2", "account-a", "trip-a", "user-a", 2))

        val duplicate = runCatching {
            database.visitOutboxDao().enqueue(
                visit("observation-2", "account-a", "trip-a", "user-a", "client-1", 2)
                    .copy(payloadFingerprint = "changed"),
            )
        }.exceptionOrNull()
        assertNotNull(duplicate)
        assertEquals(
            LocationClassificationState.PENDING,
            database.locationObservationDao().find("observation-2")?.classificationState,
        )

        val row = database.visitOutboxDao().next("account-a", "trip-a")!!
        val update = runCatching {
            database.query(
                SimpleSQLiteQuery("UPDATE visit_outbox SET payload_fingerprint = 'changed' WHERE id = ?", arrayOf(row.id)),
            ).use { it.moveToFirst() }
        }.exceptionOrNull()
        assertNotNull(update)
        assertEquals("fingerprint-client-1", database.visitOutboxDao().find(row.id)?.payloadFingerprint)
    }

    @Test
    fun visitsAreFifoPerAccountAndTripAndStopAtRetry() {
        insertAccount("account-a", "user-a")
        enqueue("account-a", "trip-a", "user-a", "observation-2", "client-2", 2)
        enqueue("account-a", "trip-a", "user-a", "observation-1", "client-1", 1)
        enqueue("account-a", "trip-b", "user-a", "observation-3", "client-3", 0)
        val sent = mutableListOf<String>()
        val outcomes = ArrayDeque<TransmissionOutcome>().apply {
            add(TransmissionOutcome.Acknowledged)
            add(TransmissionOutcome.HttpFailure(503))
        }
        val processor = TypedOutboxProcessor(
            database,
            { payload -> sent += payload.clientVisitId; outcomes.removeFirst() },
            { _, _ -> error("not used") },
        )

        assertTrue(processor.processVisits("account-a", "trip-a"))
        assertEquals(listOf("client-1", "client-2"), sent)
        assertEquals(OutboxState.ACKNOWLEDGED, row("account-a", "client-1").outboxState)
        assertEquals(RetryClass.SERVER, row("account-a", "client-2").retryClass)
        assertEquals("client-2", database.visitOutboxDao().next("account-a", "trip-a")?.clientVisitId)
        assertEquals("client-3", database.visitOutboxDao().next("account-a", "trip-b")?.clientVisitId)
    }

    @Test
    fun retryAndTerminalHttpClassesAreTypedAndTerminalRowsAreRetained() {
        assertEquals(RetryClass.NETWORK, OutboxRetryPolicy.classify(TransmissionOutcome.NetworkFailure).retryClass)
        assertEquals(RetryClass.RATE_LIMIT, OutboxRetryPolicy.classify(TransmissionOutcome.HttpFailure(429)).retryClass)
        assertEquals(RetryClass.SERVER, OutboxRetryPolicy.classify(TransmissionOutcome.HttpFailure(500)).retryClass)

        insertAccount("account-a", "user-a")
        listOf(400, 403, 409).forEachIndexed { index, _ ->
            enqueue("account-a", "trip-a", "user-a", "observation-$index", "client-$index", index.toLong())
        }
        val responses = ArrayDeque(listOf(400, 403, 409))
        val processor = TypedOutboxProcessor(
            database,
            { TransmissionOutcome.HttpFailure(responses.removeFirst()) },
            { _, _ -> error("not used") },
        )

        assertFalse(processor.processVisits("account-a", "trip-a"))
        val retained = database.visitOutboxDao().allForAccount("account-a")
        assertEquals(3, retained.size)
        assertTrue(retained.all { it.outboxState == OutboxState.TERMINAL })
        assertEquals(listOf(400, 403, 409), retained.map { it.lastResponseCode })
    }

    @Test
    fun accountSwitchCannotReadOrTransmitAnotherAccountsRows() {
        insertAccount("account-a", "user-a")
        insertAccount("account-b", "user-b")
        enqueue("account-a", "trip-shared", "user-a", "observation-a", "client-a", 1)
        enqueue("account-b", "trip-shared", "user-b", "observation-b", "client-b", 1)

        assertEquals(listOf("client-a"), database.visitOutboxDao().allForAccount("account-a").map { it.clientVisitId })
        assertEquals(listOf("client-b"), database.visitOutboxDao().allForAccount("account-b").map { it.clientVisitId })
        assertEquals("client-b", database.visitOutboxDao().next("account-b", "trip-shared")?.clientVisitId)
    }

    @Test
    fun pendingSetLogsAreMultiRecordFifoAndPayloadOwnershipIsImmutable() {
        insertAccount("account-a", "user-a")
        insertAccount("account-b", "user-b")
        val later = pendingSetLog("later", "account-a", "user-a", 9, 2)
        val first = pendingSetLog("first", "account-a", "user-a", 9, 1)
        val other = pendingSetLog("other", "account-b", "user-b", 9, 0)
        listOf(later, first, other).forEach(database.pendingSetLogDao()::insert)

        assertEquals("first", database.pendingSetLogDao().next("account-a", 9)?.clientUploadId)
        assertEquals(listOf("first", "later"), database.pendingSetLogDao().allForAccount("account-a").map { it.clientUploadId })
        assertEquals("other", database.pendingSetLogDao().next("account-b", 9)?.clientUploadId)

        val changedOwner = runCatching {
            database.query(
                SimpleSQLiteQuery("UPDATE pending_set_logs SET account_id = 'account-b' WHERE client_upload_id = 'first'"),
            ).use { it.moveToFirst() }
        }.exceptionOrNull()
        val changedHash = runCatching {
            database.query(
                SimpleSQLiteQuery("UPDATE pending_set_logs SET original_sha256 = '${"c".repeat(64)}' WHERE client_upload_id = 'first'"),
            ).use { it.moveToFirst() }
        }.exceptionOrNull()
        assertNotNull(changedOwner)
        assertNotNull(changedHash)
        assertEquals("account-a", database.pendingSetLogDao().find("first")?.accountId)
        assertEquals("a".repeat(64), database.pendingSetLogDao().find("first")?.originalSha256)
    }

    @Test
    fun shareCandidatesRequirePersistedRawImportAndRollbackAsOneClassification() {
        val missing = candidate("missing", "candidate-0", 0)
        assertNotNull(runCatching { database.shareImportDao().persistCandidates("missing", listOf(missing)) }.exceptionOrNull())

        database.shareImportDao().insertPending(pending("import-1", null, "raw-1", 1))
        val mixed = listOf(
            candidate("import-1", "candidate-1", 0),
            candidate("other", "candidate-2", 1),
        )
        assertNotNull(runCatching { database.shareImportDao().persistCandidates("import-1", mixed) }.exceptionOrNull())
        assertTrue(database.shareImportDao().candidates("import-1").isEmpty())
        assertEquals(PendingShareImportStatus.SAVED, database.shareImportDao().pending("import-1")?.status)

        database.shareImportDao().persistCandidates("import-1", listOf(candidate("import-1", "candidate-1", 0)))
        assertEquals(PendingShareImportStatus.CLASSIFIED, database.shareImportDao().pending("import-1")?.status)
    }

    @Test
    fun confirmedShareImportOutboxIsTypedFifoAndAccountScoped() {
        insertAccount("account-a", "user-a")
        insertAccount("account-b", "user-b")
        listOf("import-later" to 2L, "import-first" to 1L).forEach { (importId, createdAt) ->
            database.shareImportDao().insertPending(pending(importId, "account-a", importId, createdAt))
            val candidate = candidate(importId, "candidate-$importId", 0)
            database.shareImportDao().persistCandidates(importId, listOf(candidate))
            database.shareImportDao().insertDecision(
                ShareImportDecisionEntity(
                    candidateId = candidate.candidateId,
                    importId = importId,
                    decision = CandidateDecisionState.CONFIRMED,
                    decidedAt = createdAt,
                ),
            )
        }
        database.shareImportDao().insertPending(pending("import-b", "account-b", "import-b", 0))
        val candidateB = candidate("import-b", "candidate-b", 0)
        database.shareImportDao().persistCandidates("import-b", listOf(candidateB))
        database.shareImportDao().insertDecision(
            ShareImportDecisionEntity(candidateB.candidateId, "import-b", CandidateDecisionState.CONFIRMED, decidedAt = 0),
        )
        val sent = mutableListOf<String>()
        val processor = TypedOutboxProcessor(
            database,
            { error("not used") },
            { decision, _ ->
                sent += decision.importId
                if (decision.importId == "import-first") TransmissionOutcome.Acknowledged else TransmissionOutcome.HttpFailure(429)
            },
        )

        assertTrue(processor.processShareImports("account-a"))
        assertEquals(listOf("import-first", "import-later"), sent)
        assertEquals("candidate-import-later", database.shareImportDao().nextConfirmed("account-a")?.candidateId)
        assertEquals("candidate-b", database.shareImportDao().nextConfirmed("account-b")?.candidateId)

        val immutableDecision = runCatching {
            database.query(
                SimpleSQLiteQuery("UPDATE share_import_decisions SET decision = 'REJECTED' WHERE candidate_id = 'candidate-import-later'"),
            ).use { it.moveToFirst() }
        }.exceptionOrNull()
        assertNotNull(immutableDecision)
        assertEquals(CandidateDecisionState.CONFIRMED, database.shareImportDao().nextConfirmed("account-a")?.decision)
    }

    @Test
    fun roomVersionFourMigratesToFiveWithPendingSetLogSchema() {
        database.close()
        val name = "set-log-migration-${UUID.randomUUID()}.db"
        val schemaFile = sequenceOf(
            File("schemas/com.stog.app.core.database.StogDatabase/4.json"),
            File("app/schemas/com.stog.app.core.database.StogDatabase/4.json"),
        ).first(File::isFile)
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace(roomTablePlaceholder(), table))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { item ->
                    sqlite.execSQL(indices.getJSONObject(item).getString("createSql").replace(roomTablePlaceholder(), table))
                }
            }
            schema.getJSONArray("setupQueries").let { setup ->
                repeat(setup.length()) { sqlite.execSQL(setup.getString(it)) }
            }
            sqlite.version = 4
        }
        val upgraded = StogDatabase.build(context, name)
        onDatabaseThread {
            upgraded.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "user-a", 1))
            upgraded.pendingSetLogDao().insert(pendingSetLog("migrated", "account-a", "user-a", 9, 1))
        }
        assertEquals("migrated", onDatabaseThread { upgraded.pendingSetLogDao().next("account-a", 9)?.clientUploadId })
        assertNotNull(runCatching {
            onDatabaseThread { upgraded.openHelper.writableDatabase.execSQL(
                "UPDATE pending_set_logs SET trip_id=10 WHERE client_upload_id='migrated'",
            ) }
        }.exceptionOrNull())
        upgraded.close()
        context.deleteDatabase(name)
        database = StogDatabase.inMemory(context)
    }

    @Test
    fun processRecreationReloadsDatabaseRows() {
        database.close()
        val name = "recreation-${UUID.randomUUID()}.db"
        val first = StogDatabase.build(context, name)
        onDatabaseThread {
            first.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "user-a", 1))
            first.locationObservationDao().insert(observation("observation-1", "account-a", "trip-a", "user-a", 1))
            first.visitOutboxDao().enqueue(visit("observation-1", "account-a", "trip-a", "user-a", "client-1", 1))
        }
        first.close()

        val recreated = StogDatabase.build(context, name)
        val restored = onDatabaseThread { recreated.visitOutboxDao().next("account-a", "trip-a") }
        recreated.close()
        context.deleteDatabase(name)

        assertEquals("client-1", restored?.clientVisitId)
        database = StogDatabase.inMemory(context)
    }

    @Test
    fun recoveryDeletesInterruptedStagingAndMarksMissingOrPartialRawImportsFailed() {
        val rawRoot = File(context.cacheDir, "recovery-${UUID.randomUUID()}").also { it.mkdirs() }
        File(rawRoot, ".staging-abandoned").also { it.mkdirs(); File(it, "part").writeText("x") }
        val validMetadata = "status=SAVED\nsource=UNKNOWN\nraw_text=x\nattachment_count=0\nmention_count=0\ndecision_count=0\nmanual_count=0"
        File(rawRoot, "valid").also { it.mkdirs(); File(it, "metadata.properties").writeText(validMetadata) }
        File(rawRoot, "partial").also {
            it.mkdirs()
            File(it, "metadata.properties").writeText(validMetadata)
            File(it, "attachment.partial").writeText("x")
        }
        database.shareImportDao().insertPending(pending("missing", null, "missing", 1))
        database.shareImportDao().insertPending(pending("valid", null, "valid", 2))
        database.shareImportDao().insertPending(pending("partial", null, "partial", 3))

        val failed = ShareImportRecovery(rawRoot, database.shareImportDao()).recover()

        assertEquals(setOf("missing", "partial"), failed.toSet())
        assertEquals(PendingShareImportStatus.FAILED, database.shareImportDao().pending("missing")?.status)
        assertEquals(PendingShareImportStatus.CLASSIFIED, database.shareImportDao().pending("valid")?.status)
        assertFalse(File(rawRoot, ".staging-abandoned").exists())
        rawRoot.deleteRecursively()
    }

    private fun roomTablePlaceholder() = charArrayOf(
        '$', '{', 'T', 'A', 'B', 'L', 'E', '_', 'N', 'A', 'M', 'E', '}',
    ).concatToString()

    private fun insertAccount(accountId: String, userId: String) {
        database.accountOwnershipDao().insert(AccountOwnershipEntity(accountId, userId, 0))
    }

    private fun enqueue(accountId: String, tripId: String, userId: String, observationId: String, clientId: String, createdAt: Long) {
        database.locationObservationDao().insert(observation(observationId, accountId, tripId, userId, createdAt))
        database.visitOutboxDao().enqueue(visit(observationId, accountId, tripId, userId, clientId, createdAt))
    }

    private fun row(accountId: String, clientId: String): VisitOutboxEntity =
        database.visitOutboxDao().allForAccount(accountId).single { it.clientVisitId == clientId }

    private fun observation(id: String, account: String, trip: String, user: String, time: Long) =
        LocationObservationEntity(id, account, trip, user, 617733123456789012L, 35.8, 127.1, time)

    private fun visit(observation: String, account: String, trip: String, user: String, client: String, time: Long) =
        VisitOutboxEntity(
            observationId = observation,
            accountId = account,
            tripId = trip,
            userId = user,
            clientVisitId = client,
            payloadFingerprint = "fingerprint-$client",
            cellId = 617733123456789012L,
            lat = 35.8,
            lng = 127.1,
            enteredAt = time,
            leftAt = time + 1,
            status = VisitStatus.VISITED,
            isInterpolated = false,
            createdAt = time,
        )

    private fun pendingSetLog(id: String, account: String, user: String, trip: Long, time: Long) =
        PendingSetLogEntity(
            id, account, user, trip, "camera", "/owned/$id/original", "/owned/$id/thumbnail", null,
            10, 5, "a".repeat(64), "b".repeat(64), null, null, null, null,
            "2026-08-25T00:00:00Z", null, "no_match", null, "private", false, createdAt = time,
        )

    private fun pending(id: String, account: String?, directory: String, time: Long) =
        PendingShareImportEntity(id, account, directory, "raw", null, "text/plain", "UNKNOWN", createdAt = time)

    private fun candidate(importId: String, id: String, order: Int) =
        ShareImportCandidateEntity(id, importId, CandidateOrigin.EXTRACTED, "장소 $order", null, null, order)

    private fun <T> onDatabaseThread(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { block() }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
