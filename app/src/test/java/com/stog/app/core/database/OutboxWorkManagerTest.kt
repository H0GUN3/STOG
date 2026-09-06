package com.stog.app.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.stog.app.feature.auth.StoredAuthTokens
import com.stog.app.feature.record.CollectorState
import com.stog.app.feature.record.MemberRecordingState
import com.stog.app.feature.record.PermissionState
import com.stog.app.feature.record.toEntity
import com.stog.app.feature.plan.share_import.ConfirmedShareHttpTransport
import com.stog.app.feature.plan.share_import.ShareImportFileStore
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.PlaceSearchProvenance
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.basketRequestPayload
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OutboxWorkManagerTest {
    private val directExecutor = Executor(Runnable::run)
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }

    @After
    fun cleanDatabase() {
        OutboxRuntime.reset()
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
    }

    @Test
    fun uniqueKeepSchedulingAndWorkerRetryAreAccountTripScopedAcrossRecreation() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(directExecutor)
                .setTaskExecutor(directExecutor)
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val scheduler = OutboxWorkScheduler(context)

        val firstId = scheduler.enqueueVisits("account-a", "trip-a")
        scheduler.enqueueVisits("account-a", "trip-a")
        val otherTripId = scheduler.enqueueVisits("account-a", "trip-b")
        val otherAccountId = scheduler.enqueueShareImports("account-b", 9)

        val sameScope = workManager.getWorkInfosForUniqueWork(
            OutboxWorkScheduler.visitWorkName("account-a", "trip-a"),
        ).get(10, TimeUnit.SECONDS)
        assertEquals(2, sameScope.size)
        assertTrue(sameScope.any { it.id == firstId })
        val firstWork = sameScope.first { it.id == firstId }
        assertEquals(WorkInfo.State.ENQUEUED, firstWork.state)
        assertEquals(NetworkType.CONNECTED, firstWork.constraints.requiredNetworkType)
        assertTrue(setOf("outbox-kind:visit", "account:account-a", "trip:trip-a").all(firstWork.tags::contains))
        assertEquals(
            otherTripId,
            workManager.getWorkInfosForUniqueWork(OutboxWorkScheduler.visitWorkName("account-a", "trip-b"))
                .get(10, TimeUnit.SECONDS).single().id,
        )
        assertEquals(
            otherAccountId,
            workManager.getWorkInfosForUniqueWork(OutboxWorkScheduler.shareImportWorkName("account-b", 9))
                .get(10, TimeUnit.SECONDS).single().id,
        )

        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
        offMain {
            val database = StogDatabase.get(context)
            database.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "user-a", 1))
            database.locationObservationDao().insert(observation())
            database.visitOutboxDao().enqueue(visit())
        }
        val worker = TestWorkerBuilder<TypedOutboxWorker>(
            context = context,
            executor = directExecutor,
            inputData = workDataOf(
                TypedOutboxWorker.KEY_KIND to TypedOutboxWorker.KIND_VISIT,
                TypedOutboxWorker.KEY_ACCOUNT_ID to "account-a",
                TypedOutboxWorker.KEY_TRIP_ID to "trip-a",
            ),
        ).build()
        val retry = offMain { worker.doWork() }
        assertEquals(ListenableWorker.Result.retry().javaClass, retry.javaClass)
        assertEquals(RetryClass.NETWORK, offMain { StogDatabase.get(context).visitOutboxDao().next("account-a", "trip-a")?.retryClass })

        StogDatabase.resetForHostTest()
        val recreated = offMain { StogDatabase.get(context).visitOutboxDao().next("account-a", "trip-a") }
        assertEquals("client-a", recreated?.clientVisitId)
        val isolatedWorker = TestWorkerBuilder<TypedOutboxWorker>(
            context = context,
            executor = directExecutor,
            inputData = workDataOf(
                TypedOutboxWorker.KEY_KIND to TypedOutboxWorker.KIND_VISIT,
                TypedOutboxWorker.KEY_ACCOUNT_ID to "account-b",
                TypedOutboxWorker.KEY_TRIP_ID to "trip-a",
            ),
        ).build()
        val isolated = offMain { isolatedWorker.doWork() }
        assertEquals(ListenableWorker.Result.success().javaClass, isolated.javaClass)
        assertEquals(1, offMain { StogDatabase.get(context).visitOutboxDao().next("account-a", "trip-a")?.attemptCount })

        OutboxRuntime.loadTokens = { StoredAuthTokens("new-token", "refresh", 99) }
        val staleAccountWorker = TestWorkerBuilder<TypedOutboxWorker>(
            context = context,
            executor = directExecutor,
            inputData = workDataOf(
                TypedOutboxWorker.KEY_KIND to TypedOutboxWorker.KIND_VISIT,
                TypedOutboxWorker.KEY_ACCOUNT_ID to "account-a",
                TypedOutboxWorker.KEY_TRIP_ID to "trip-a",
            ),
        ).build()
        val staleResult = offMain { staleAccountWorker.doWork() }
        assertEquals(ListenableWorker.Result.success().javaClass, staleResult.javaClass)
        assertEquals(1, offMain { StogDatabase.get(context).visitOutboxDao().next("account-a", "trip-a")?.attemptCount })
    }

    @Test
    fun startupRecoveryEnumeratesEveryPendingScopeAndRepeatedRecoveryIsIdempotent() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(directExecutor).setTaskExecutor(directExecutor)
                .setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
        offMain {
            val database = StogDatabase.get(context)
            database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", 1))
            database.accountOwnershipDao().insert(AccountOwnershipEntity("8", "8", 1))
            insertPartiallyAcknowledgedShare(database)
            insertPendingShare(database, "a", "7", 9, 1)
            insertPendingShare(database, "b", "7", 10, 2)
            insertPendingShare(database, "c", "7", 9, 3)
            database.shareImportDao().recordOutcome("c-candidate", OutboxState.RETRY, RetryClass.SERVER, 503)
            insertPendingShare(database, "stale", "8", 11, 4)
        }
        StogDatabase.resetForHostTest()
        val first = offMain { PendingShareWorkInitializer.schedule(context, "7") }
        val second = offMain { PendingShareWorkInitializer.schedule(context, "7") }
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        val manager = WorkManager.getInstance(context)
        assertEquals(2, manager.getWorkInfosForUniqueWork(OutboxWorkScheduler.shareImportWorkName("7", 9)).get(10, TimeUnit.SECONDS).size)
        assertEquals(2, manager.getWorkInfosForUniqueWork(OutboxWorkScheduler.shareImportWorkName("7", 10)).get(10, TimeUnit.SECONDS).size)
        assertTrue(manager.getWorkInfosForUniqueWork(OutboxWorkScheduler.shareImportWorkName("8", 11)).get(10, TimeUnit.SECONDS).isEmpty())
        assertEquals(
            listOf(PendingShareScope("7", 9), PendingShareScope("7", 10), PendingShareScope("8", 11)),
            offMain { StogDatabase.get(context).shareImportDao().pendingConfirmedScopes() },
        )
        val remaining = offMain { StogDatabase.get(context).shareImportDao().nextConfirmed("7", 9) }
        assertEquals("partial-remaining", remaining?.candidateId)
        assertEquals(
            OutboxState.ACKNOWLEDGED,
            offMain { StogDatabase.get(context).shareImportDao().decisions("partial").single { it.candidateId == "partial-acked" }.syncState },
        )
    }

    @Test
    fun setLogStartupRecoveryAndWorkerAreAccountScoped() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(directExecutor).setTaskExecutor(directExecutor)
                .setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
        val root = File(context.cacheDir, "set-log-worker").also { it.deleteRecursively(); it.mkdirs() }
        offMain {
            val database = StogDatabase.get(context)
            database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", 1))
            database.accountOwnershipDao().insert(AccountOwnershipEntity("8", "8", 1))
            database.pendingSetLogDao().insert(pendingSetLog(root, "active", "7", 9, 1))
            database.pendingSetLogDao().insert(pendingSetLog(root, "other", "8", 9, 2))
        }

        val first = offMain { PendingSetLogWorkInitializer.schedule(context, "7") }
        val second = offMain { PendingSetLogWorkInitializer.schedule(context, "7") }
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        val manager = WorkManager.getInstance(context)
        val scheduled = manager.getWorkInfosForUniqueWork(OutboxWorkScheduler.setLogWorkName("7", 9))
            .get(10, TimeUnit.SECONDS)
        assertEquals(2, scheduled.size)
        assertTrue(manager.getWorkInfosForUniqueWork(OutboxWorkScheduler.setLogWorkName("8", 9))
            .get(10, TimeUnit.SECONDS).isEmpty())

        OutboxRuntime.loadTokens = { StoredAuthTokens("access", "refresh", 7) }
        val sent = mutableListOf<String>()
        OutboxRuntime.setLogTransport = { _, _ -> SetLogOutboxTransport { payload ->
            sent += payload.clientUploadId
            SetLogTransmissionOutcome.Acknowledged(71, "private")
        } }
        StogDatabase.resetForHostTest()
        val worker = TestWorkerBuilder<TypedOutboxWorker>(
            context = context,
            executor = directExecutor,
            inputData = workDataOf(
                TypedOutboxWorker.KEY_KIND to TypedOutboxWorker.KIND_SET_LOG,
                TypedOutboxWorker.KEY_ACCOUNT_ID to "7",
                TypedOutboxWorker.KEY_TRIP_ID to 9L,
            ),
        ).build()

        assertEquals(ListenableWorker.Result.success().javaClass, offMain { worker.doWork() }.javaClass)
        assertEquals(listOf("active"), sent)
        assertEquals(SetLogOutboxState.ACKNOWLEDGED, offMain {
            StogDatabase.get(context).pendingSetLogDao().find("active")?.outboxState
        })
        assertEquals(SetLogOutboxState.PENDING, offMain {
            StogDatabase.get(context).pendingSetLogDao().find("other")?.outboxState
        })
        root.deleteRecursively()
    }

    @Test
    fun setLogQueueBootstrapsFreshOwnershipAndRejectsMismatchWithoutConcurrentInsertRace() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(directExecutor).setTaskExecutor(directExecutor)
                .setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
        val database = StogDatabase.inMemory(context)
        val root = File(context.cacheDir, "set-log-fresh-owner").also { it.deleteRecursively(); it.mkdirs() }
        val queue = SetLogDeliveryQueue(context, database, OutboxWorkScheduler(context))
        try {
            queue.enqueue(pendingSetLog(root, "fresh", "fresh-account", 9, 1))
            assertEquals("fresh-account", database.accountOwnershipDao().find("fresh-account")?.userId)
            assertEquals("fresh", database.pendingSetLogDao().find("fresh")?.clientUploadId)

            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val inserts = listOf("concurrent-a", "concurrent-b").mapIndexed { index, id ->
                    executor.submit {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        queue.enqueue(pendingSetLog(root, id, "concurrent-account", 10, index.toLong()))
                    }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                inserts.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            assertEquals("concurrent-account", database.accountOwnershipDao().find("concurrent-account")?.userId)
            assertEquals(
                listOf("concurrent-a", "concurrent-b"),
                database.pendingSetLogDao().allForAccount("concurrent-account").map { it.clientUploadId }.sorted(),
            )

            database.accountOwnershipDao().insert(AccountOwnershipEntity("mismatch-account", "other-user", 1))
            val mismatch = pendingSetLog(root, "mismatch", "mismatch-account", 11, 1)
            assertTrue(runCatching { queue.enqueue(mismatch) }.isFailure)
            assertEquals(null, database.pendingSetLogDao().find("mismatch"))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun directShareWorkerSmokeUsesInjectedBoundaries() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(directExecutor).setTaskExecutor(directExecutor)
                .setMinimumLoggingLevel(android.util.Log.ERROR).build(),
        )
        val rawRoot = File(context.noBackupFilesDir, ShareImportFileStore.ROOT_DIRECTORY_NAME)
        val raw = File(rawRoot, "share-work").also { it.mkdirs(); File(it, "raw").writeText("secret") }
        val payload = basketRequestPayload(
            9,
            PlaceSearchCandidate("external", "place", "address", 35.8, 127.1, listOf("cafe")),
            "client-key",
        )
        offMain {
            val database = StogDatabase.get(context)
            database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", 1))
            database.shareImportDao().insertPending(PendingShareImportEntity(
                "share-work", "7", "share-work", "secret", null, "text/plain", "UNKNOWN",
                PendingShareImportStatus.CLASSIFIED, 1,
            ))
            database.shareImportDao().insertCandidate(ShareImportCandidateEntity(
                "share-candidate", "share-work", CandidateOrigin.MANUAL, "place", "address", null, 0,
                "google", "external", 35.8, 127.1, "cafe", null, null,
            ))
            database.shareImportDao().insertDecision(ShareImportDecisionEntity(
                "share-candidate", "share-work", CandidateDecisionState.CONFIRMED, 9,
                "client-key", payload.payloadFingerprint, decidedAt = 1,
            ))
        }
        val sent = mutableListOf<String>()
        val sentBodies = mutableListOf<JSONObject>()
        val responseCode = AtomicInteger(200)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/basket-items") { exchange ->
                sent += exchange.requestHeaders.getFirst("Authorization")
                sentBodies += JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                val response = if (responseCode.get() == 200) {
                    "{\"id\":1,\"place_id\":2,\"cell_id\":null,\"status\":\"resolved\"}".toByteArray()
                } else "{\"status\":${responseCode.get()}}".toByteArray()
                exchange.sendResponseHeaders(responseCode.get(), response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        OutboxRuntime.reset()
        OutboxRuntime.apiBaseUrl = { "http://127.0.0.1:${server.address.port}" }
        OutboxRuntime.loadTokens = { StoredAuthTokens("access", "refresh", 7) }
        val scheduler = OutboxWorkScheduler(context)
        val first = scheduler.enqueueShareImports("7", 9)
        scheduler.enqueueShareImports("7", 9)
        val shareWork = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
            OutboxWorkScheduler.shareImportWorkName("7", 9),
        ).get(10, TimeUnit.SECONDS)
        assertEquals(2, shareWork.size)

        StogDatabase.resetForHostTest()
        val worker = TestWorkerBuilder<TypedOutboxWorker>(
            context = context, executor = directExecutor,
            inputData = workDataOf(
                TypedOutboxWorker.KEY_KIND to TypedOutboxWorker.KIND_SHARE_IMPORT,
                TypedOutboxWorker.KEY_ACCOUNT_ID to "7",
                TypedOutboxWorker.KEY_TRIP_ID to 9L,
            ),
        ).build()
        assertEquals(ListenableWorker.Result.success().javaClass, offMain { worker.doWork() }.javaClass)
        assertEquals(listOf("Bearer access"), sent)
        assertEquals("google", sentBodies.single().getString("provider"))
        assertEquals("external", sentBodies.single().getString("external_id"))
        assertTrue(!raw.exists())
        assertEquals(null, offMain { StogDatabase.get(context).shareImportDao().pending("share-work") })
        assertTrue(shareWork.any { it.id == first })

        val canonicalRaw = File(rawRoot, "share-canonical").also { it.mkdirs(); File(it, "raw").writeText("secret") }
        val canonicalPlace = PlaceSearchCandidate(
            "canonical-external", "canonical", "address", 35.9, 127.2, listOf("cafe"),
            provenance = PlaceSearchProvenance.Canonical(12, "public_data", 13, "public"),
        )
        val canonicalPayload = basketRequestPayload(9, canonicalPlace, "canonical-key")
        offMain {
            val database = StogDatabase.get(context)
            database.shareImportDao().insertPending(PendingShareImportEntity(
                "share-canonical", "7", "share-canonical", "secret", null, "text/plain", "UNKNOWN",
                PendingShareImportStatus.CLASSIFIED, 2,
            ))
            database.shareImportDao().insertCandidate(ShareImportCandidateEntity(
                "canonical-candidate", "share-canonical", CandidateOrigin.EXTRACTED, "canonical", "address", null, 0,
                "canonical", "canonical-external", 35.9, 127.2, "cafe", 12, 13,
            ))
            database.shareImportDao().insertDecision(ShareImportDecisionEntity(
                "canonical-candidate", "share-canonical", CandidateDecisionState.CONFIRMED, 9,
                "canonical-key", canonicalPayload.payloadFingerprint, decidedAt = 2,
            ))
        }
        assertEquals(ListenableWorker.Result.success().javaClass, offMain { worker.doWork() }.javaClass)
        assertTrue(!canonicalRaw.exists())
        assertEquals("canonical", sentBodies.last().getString("provider"))
        assertEquals(12L, sentBodies.last().getLong("canonical_place_id"))
        assertEquals(13L, sentBodies.last().getLong("canonical_source_id"))

        val retryRaw = File(rawRoot, "share-retry").also { it.mkdirs(); File(it, "raw").writeText("secret") }
        val retryPayload = basketRequestPayload(
            9, PlaceSearchCandidate("retry-external", "retry", "address", 35.8, 127.1, listOf("cafe")), "retry-key",
        )
        offMain {
            val database = StogDatabase.get(context)
            database.shareImportDao().insertPending(PendingShareImportEntity(
                "share-retry", "7", "share-retry", "secret", null, "text/plain", "UNKNOWN",
                PendingShareImportStatus.CLASSIFIED, 2,
            ))
            database.shareImportDao().insertCandidate(ShareImportCandidateEntity(
                "retry-candidate", "share-retry", CandidateOrigin.MANUAL, "retry", "address", null, 0,
                "google", "retry-external", 35.8, 127.1, "cafe", null, null,
            ))
            database.shareImportDao().insertDecision(ShareImportDecisionEntity(
                "retry-candidate", "share-retry", CandidateDecisionState.CONFIRMED, 9,
                "retry-key", retryPayload.payloadFingerprint, decidedAt = 2,
            ))
        }
        responseCode.set(503)
        assertEquals(ListenableWorker.Result.retry().javaClass, offMain { worker.doWork() }.javaClass)
        assertEquals(RetryClass.SERVER, offMain { StogDatabase.get(context).shareImportDao().nextConfirmed("7", 9)?.retryClass })
        assertTrue(retryRaw.exists())
        responseCode.set(400)
        assertEquals(ListenableWorker.Result.success().javaClass, offMain { worker.doWork() }.javaClass)
        assertEquals(OutboxState.TERMINAL, offMain { StogDatabase.get(context).shareImportDao().decisions("share-retry").single().syncState })
        assertTrue(retryRaw.exists())
        server.stop(0)
    }

    @Test
    fun collectionStatePublicationRetriesAndReconcilesOptimisticVersion() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(directExecutor)
                .setTaskExecutor(directExecutor)
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .build(),
        )
        offMain {
            val database = StogDatabase.get(context)
            database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", 1))
            database.memberCollectionStateDao().save(
                MemberRecordingState(
                    "7", "trip-a", "7", CollectorState.BLOCKED, PermissionState.REVOKED, modeVersion = 2,
                ).toEntity(1),
            )
        }
        OutboxRuntime.loadTokens = { StoredAuthTokens("token", "refresh", 7) }
        val outcomes = ArrayDeque<CollectionPublishOutcome>().apply {
            add(CollectionPublishOutcome.NetworkFailure)
            add(CollectionPublishOutcome.HttpFailure(429))
            add(CollectionPublishOutcome.HttpFailure(503))
            add(CollectionPublishOutcome.HttpFailure(400))
            add(CollectionPublishOutcome.Acknowledged(3))
        }
        val seenVersions = mutableListOf<Long>()
        OutboxRuntime.publishCollection = { _, _, state ->
            seenVersions += state.modeVersion
            outcomes.removeFirst()
        }

        assertEquals(ListenableWorker.Result.retry().javaClass, offMain { collectionWorker().doWork() }.javaClass)
        assertEquals(ListenableWorker.Result.retry().javaClass, offMain { collectionWorker().doWork() }.javaClass)
        assertEquals(ListenableWorker.Result.retry().javaClass, offMain { collectionWorker().doWork() }.javaClass)
        assertEquals(ListenableWorker.Result.success().javaClass, offMain { collectionWorker().doWork() }.javaClass)
        assertEquals(ListenableWorker.Result.success().javaClass, offMain { collectionWorker().doWork() }.javaClass)
        assertEquals(listOf(2L, 2L, 2L, 2L, 2L), seenVersions)
        assertEquals(3L, offMain {
            StogDatabase.get(context).memberCollectionStateDao().find("7", "trip-a", "7")?.modeVersion
        })
    }

    private fun insertPartiallyAcknowledgedShare(database: StogDatabase) {
        database.shareImportDao().insertPending(PendingShareImportEntity(
            "partial", "7", "partial", "raw", null, "text/plain", "UNKNOWN",
            PendingShareImportStatus.CLASSIFIED, 0,
        ))
        listOf("partial-acked", "partial-remaining").forEachIndexed { index, candidateId ->
            database.shareImportDao().insertCandidate(ShareImportCandidateEntity(
                candidateId, "partial", CandidateOrigin.MANUAL, candidateId, "address", null, index,
                "google", "$candidateId-external", 35.8, 127.1, "cafe", null, null,
            ))
            database.shareImportDao().insertDecision(ShareImportDecisionEntity(
                candidateId, "partial", CandidateDecisionState.CONFIRMED, 9,
                "$candidateId-key", candidateId.padEnd(64, 'a').take(64), decidedAt = index.toLong(),
            ))
        }
        database.shareImportDao().recordOutcome("partial-acked", OutboxState.ACKNOWLEDGED, RetryClass.NONE, null)
        database.shareImportDao().recordOutcome("partial-remaining", OutboxState.RETRY, RetryClass.SERVER, 503)
    }

    private fun insertPendingShare(database: StogDatabase, id: String, account: String, trip: Long, created: Long) {
        database.shareImportDao().insertPending(PendingShareImportEntity(
            id, account, id, "raw", null, "text/plain", "UNKNOWN", PendingShareImportStatus.CLASSIFIED, created,
        ))
        database.shareImportDao().insertCandidate(ShareImportCandidateEntity(
            "$id-candidate", id, CandidateOrigin.MANUAL, "place", "address", null, 0,
            "google", "$id-external", 35.8, 127.1, "cafe", null, null,
        ))
        database.shareImportDao().insertDecision(ShareImportDecisionEntity(
            "$id-candidate", id, CandidateDecisionState.CONFIRMED, trip,
            "$id-key", id.padEnd(64, 'a').take(64), decidedAt = created,
        ))
    }

    private fun pendingSetLog(
        root: File,
        id: String,
        account: String,
        trip: Long,
        created: Long,
    ): PendingSetLogEntity {
        val directory = File(root, id).also(File::mkdirs)
        val original = File(directory, "original.jpg").also { it.writeText("original") }
        val thumbnail = File(directory, "thumbnail.jpg").also { it.writeText("thumbnail") }
        return PendingSetLogEntity(
            id, account, account, trip, "camera", original.absolutePath, thumbnail.absolutePath, null,
            original.length(), thumbnail.length(), "a".repeat(64), "b".repeat(64),
            null, null, null, null, "2026-08-25T00:00:00Z", null, "no_match", null,
            "private", false, createdAt = created,
        )
    }

    private fun collectionWorker() = TestWorkerBuilder<TypedOutboxWorker>(
        context = context,
        executor = directExecutor,
        inputData = workDataOf(
            TypedOutboxWorker.KEY_KIND to TypedOutboxWorker.KIND_COLLECTION_STATE,
            TypedOutboxWorker.KEY_ACCOUNT_ID to "7",
            TypedOutboxWorker.KEY_TRIP_ID to "trip-a",
        ),
    ).build()

    private fun observation() = LocationObservationEntity(
        "observation-a", "account-a", "trip-a", "user-a", 1, 35.8, 127.1, 1,
    )

    private fun visit() = VisitOutboxEntity(
        observationId = "observation-a",
        accountId = "account-a",
        tripId = "trip-a",
        userId = "user-a",
        clientVisitId = "client-a",
        payloadFingerprint = "fingerprint",
        cellId = 1,
        lat = 35.8,
        lng = 127.1,
        enteredAt = 1,
        leftAt = 2,
        status = VisitStatus.VISITED,
        isInterpolated = false,
        createdAt = 1,
    )

    private fun <T> offMain(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { block() }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
