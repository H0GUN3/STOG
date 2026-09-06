package com.stog.app.core.database

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.stog.app.feature.auth.AuthTokenStore
import com.stog.app.feature.auth.StogAuthSession
import com.stog.app.feature.plan.share_import.ShareImportFileStore
import com.stog.app.feature.space.PlaceSearchCandidate
import com.stog.app.feature.space.PlaceSearchProvenance
import com.stog.app.feature.space.basketRequestPayload
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [TestMasterKeys::class, TestEncryptedSharedPreferences::class],
)
class ConfirmedShareEnqueuedWorkTest {
    private val directExecutor = Executor(Runnable::run)
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }
    private lateinit var manager: WorkManager
    private lateinit var driver: TestDriver
    private val requestCompleted = Semaphore(0)

    @After
    fun clean() {
        OutboxRuntime.reset()
        runCatching { AuthTokenStore(context).clear() }
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
        File(context.noBackupFilesDir, ShareImportFileStore.ROOT_DIRECTORY_NAME).deleteRecursively()
        System.clearProperty("sun.net.http.retryPost")
        workerExecutor.shutdownNow()
    }

    @Test
    fun enqueuedDefaultWorkerUsesLoginTokenAndEnforcesFifoRetryTerminalAndCleanup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(workerExecutor)
                .setTaskExecutor(directExecutor)
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .build(),
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        manager = WorkManager.getInstance(context)
        driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        AuthTokenStore(context).save(StogAuthSession("login-access", "login-refresh", 7, "login-user"))
        assertEquals(7L, AuthTokenStore(context).load()?.userId)

        val rawRoot = File(context.noBackupFilesDir, ShareImportFileStore.ROOT_DIRECTORY_NAME)
        val acceptedRaw = rawDirectory(rawRoot, "accepted")
        val terminal400Raw = rawDirectory(rawRoot, "terminal-400")
        val terminal403Raw = rawDirectory(rawRoot, "terminal-403")
        val terminal409Raw = rawDirectory(rawRoot, "terminal-409")
        val otherTripRaw = rawDirectory(rawRoot, "other-trip")
        rawDirectory(rawRoot, "stale-account")
        offMain {
            val database = StogDatabase.get(context)
            database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", 1))
            database.accountOwnershipDao().insert(AccountOwnershipEntity("8", "8", 1))
            insertShare(database, "accepted", "provider", "7", 9, 1, providerPlace(), "provider-key")
            insertShare(database, "accepted", "canonical", "7", 9, 2, canonicalPlace(), "canonical-key")
            insertShare(database, "terminal-400", "candidate-400", "7", 9, 3, providerPlace("400"), "terminal-400-key")
            insertShare(database, "terminal-403", "candidate-403", "7", 9, 4, providerPlace("403"), "terminal-403-key")
            insertShare(database, "terminal-409", "candidate-409", "7", 9, 5, providerPlace("409"), "terminal-409-key")
            insertShare(database, "other-trip", "candidate-trip-10", "7", 10, 6, providerPlace("trip-10"), "trip-10-key")
            insertShare(database, "stale-account", "candidate-stale", "8", 9, 7, providerPlace("stale"), "stale-key")
        }
        StogDatabase.resetForHostTest()

        System.setProperty("sun.net.http.retryPost", "false")
        val actions = ArrayDeque(listOf(
            HttpAction.NETWORK, HttpAction.RATE_LIMIT, HttpAction.SERVER_500, HttpAction.SERVER_503,
            HttpAction.OK, HttpAction.OK, HttpAction.CLIENT_400, HttpAction.CLIENT_403, HttpAction.CLIENT_409,
            HttpAction.OK,
        ))
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        val ackSeenBeforeCanonical = AtomicBoolean(false)
        val rawSeenBeforeCanonicalAck = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/basket-items") { exchange ->
                try {
                    val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                    requests += CapturedRequest(exchange.requestHeaders.getFirst("Authorization"), body)
                    if (body.getString("client_item_id") == "canonical-key") {
                        ackSeenBeforeCanonical.set(offMain {
                            StogDatabase.get(context).shareImportDao().decisions("accepted")
                                .single { it.candidateId == "provider" }.syncState == OutboxState.ACKNOWLEDGED
                        })
                        rawSeenBeforeCanonicalAck.set(acceptedRaw.exists())
                    }
                    val action = synchronized(actions) { actions.removeFirst() }
                    if (action == HttpAction.NETWORK) {
                        exchange.close()
                    } else {
                        val response = if (action.code == 200) {
                            "{\"id\":1,\"place_id\":2,\"cell_id\":null,\"status\":\"resolved\"}"
                        } else "{\"status\":${action.code}}"
                        val bytes = response.toByteArray()
                        exchange.sendResponseHeaders(action.code, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                } finally {
                    requestCompleted.release()
                }
            }
            start()
        }
        try {
            OutboxRuntime.reset()
            OutboxRuntime.apiBaseUrl = { "http://127.0.0.1:${server.address.port}" }
            var workId = enqueueAttempt("7", 9)
            awaitRequests(1)
            assertRetry(workId, RetryClass.NETWORK, 1)
            assertEquals(listOf("provider-key"), clientKeys(requests))

            manager.cancelWorkById(workId).result.get(10, TimeUnit.SECONDS)
            workId = enqueueAttempt("7", 9)
            awaitRequests(1)
            assertRetry(workId, RetryClass.RATE_LIMIT, 2)
            manager.cancelWorkById(workId).result.get(10, TimeUnit.SECONDS)
            workId = enqueueAttempt("7", 9)
            awaitRequests(1)
            assertRetry(workId, RetryClass.SERVER, 3)
            manager.cancelWorkById(workId).result.get(10, TimeUnit.SECONDS)
            workId = enqueueAttempt("7", 9)
            awaitRequests(1)
            assertRetry(workId, RetryClass.SERVER, 4)
            assertEquals(listOf("provider-key", "provider-key", "provider-key", "provider-key"), clientKeys(requests))
            assertEquals(0, decision("accepted", "canonical").attemptCount)
            assertTrue(acceptedRaw.exists())

            manager.cancelWorkById(workId).result.get(10, TimeUnit.SECONDS)
            workId = enqueueAttempt("7", 9)
            awaitRequests(5)
            assertEquals(WorkInfo.State.SUCCEEDED, awaitState(workId, WorkInfo.State.SUCCEEDED).state)
            assertTrue(ackSeenBeforeCanonical.get())
            assertTrue(rawSeenBeforeCanonicalAck.get())
            assertFalse(acceptedRaw.exists())
            assertTrue(terminal400Raw.exists())
            assertTrue(terminal403Raw.exists())
            assertTrue(terminal409Raw.exists())
            assertEquals(OutboxState.TERMINAL, decision("terminal-400", "candidate-400").syncState)
            assertEquals(OutboxState.TERMINAL, decision("terminal-403", "candidate-403").syncState)
            assertEquals(OutboxState.TERMINAL, decision("terminal-409", "candidate-409").syncState)
            assertTrue(otherTripRaw.exists())
            assertEquals(OutboxState.PENDING, decision("other-trip", "candidate-trip-10").syncState)
            assertEquals(OutboxState.PENDING, decision("stale-account", "candidate-stale").syncState)

            val expectedScopeOrder = listOf(
                "provider-key", "provider-key", "provider-key", "provider-key", "provider-key",
                "canonical-key", "terminal-400-key", "terminal-403-key", "terminal-409-key",
            )
            assertEquals(expectedScopeOrder, clientKeys(requests))
            assertTrue(requests.all { it.authorization == "Bearer login-access" })
            val providerBody = requests.first().body
            assertEquals("google", providerBody.getString("provider"))
            assertEquals("provider-external", providerBody.getString("external_id"))
            val canonicalBody = requests.single { it.body.getString("client_item_id") == "canonical-key" }.body
            assertEquals("canonical", canonicalBody.getString("provider"))
            assertEquals(41L, canonicalBody.getLong("canonical_place_id"))
            assertEquals(73L, canonicalBody.getLong("canonical_source_id"))

            val otherTripId = enqueueAttempt("7", 10)
            awaitRequests(1)
            assertEquals(WorkInfo.State.SUCCEEDED, awaitState(otherTripId, WorkInfo.State.SUCCEEDED).state)
            assertFalse(otherTripRaw.exists())
            assertEquals(expectedScopeOrder + "trip-10-key", clientKeys(requests))
            assertFalse(clientKeys(requests).contains("stale-key"))
            assertTrue(actions.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    private fun enqueueAttempt(accountId: String, tripId: Long): java.util.UUID {
        val request = OneTimeWorkRequestBuilder<TypedOutboxWorker>()
            .setInputData(workDataOf(
                TypedOutboxWorker.KEY_KIND to TypedOutboxWorker.KIND_SHARE_IMPORT,
                TypedOutboxWorker.KEY_ACCOUNT_ID to accountId,
                TypedOutboxWorker.KEY_TRIP_ID to tripId,
            ))
            .build()
        manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
        driver.setAllConstraintsMet(request.id)
        return request.id
    }

    private fun awaitRequests(count: Int) {
        repeat(count) { assertTrue(requestCompleted.tryAcquire(10, TimeUnit.SECONDS)) }
        workerExecutor.submit {}.get(10, TimeUnit.SECONDS)
        workerExecutor.submit {}.get(10, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }

    private fun assertRetry(id: java.util.UUID, retryClass: RetryClass, attemptCount: Int) {
        assertEquals(WorkInfo.State.ENQUEUED, awaitState(id, WorkInfo.State.ENQUEUED).state)
        val decision = decision("accepted", "provider")
        assertEquals(retryClass, decision.retryClass)
        assertEquals(attemptCount, decision.attemptCount)
    }

    private fun awaitState(id: java.util.UUID, state: WorkInfo.State): WorkInfo =
        requireNotNull(manager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)).also {
            assertEquals(state, it.state)
        }

    private fun decision(importId: String, candidateId: String) = offMain {
        StogDatabase.get(context).shareImportDao().decisions(importId).single { it.candidateId == candidateId }
    }

    private fun clientKeys(requests: List<CapturedRequest>): List<String> =
        synchronized(requests) { requests.map { it.body.getString("client_item_id") } }

    private fun rawDirectory(root: File, importId: String): File =
        File(root, importId).also { it.mkdirs(); File(it, "raw").writeText("secret") }

    private fun insertShare(
        database: StogDatabase,
        importId: String,
        candidateId: String,
        accountId: String,
        tripId: Long,
        createdAt: Long,
        place: PlaceSearchCandidate,
        clientItemId: String,
    ) {
        if (database.shareImportDao().pending(importId) == null) {
            database.shareImportDao().insertPending(PendingShareImportEntity(
                importId, accountId, importId, "raw", null, "text/plain", "UNKNOWN",
                PendingShareImportStatus.CLASSIFIED, createdAt,
            ))
        }
        database.shareImportDao().insertCandidate(ShareImportCandidateEntity(
            candidateId, importId, CandidateOrigin.CORRECTED, place.name, place.address, null, createdAt.toInt(),
            if (place.provenance is PlaceSearchProvenance.Canonical) "canonical" else "google",
            place.externalId, place.latitude, place.longitude, place.types.first(),
            (place.provenance as? PlaceSearchProvenance.Canonical)?.placeId,
            (place.provenance as? PlaceSearchProvenance.Canonical)?.sourceId,
        ))
        val payload = basketRequestPayload(tripId, place, clientItemId)
        database.shareImportDao().insertDecision(ShareImportDecisionEntity(
            candidateId, importId, CandidateDecisionState.CONFIRMED, tripId,
            clientItemId, payload.payloadFingerprint, decidedAt = createdAt,
        ))
    }

    private fun providerPlace(suffix: String = "provider") = PlaceSearchCandidate(
        "$suffix-external", "$suffix place", "$suffix address", 35.8, 127.1, listOf("cafe"),
    )

    private fun canonicalPlace() = PlaceSearchCandidate(
        "canonical-external", "canonical place", "canonical address", 36.0, 128.0, listOf("museum"),
        provenance = PlaceSearchProvenance.Canonical(41, "public_data", 73, "public"),
    )

    private fun <T> offMain(block: () -> T): T {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { block() }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private data class CapturedRequest(val authorization: String, val body: JSONObject)

    private enum class HttpAction(val code: Int) {
        NETWORK(-1), RATE_LIMIT(429), SERVER_500(500), SERVER_503(503), OK(200),
        CLIENT_400(400), CLIENT_403(403), CLIENT_409(409),
    }
}

@Implements(MasterKeys::class)
class TestMasterKeys {
    companion object {
        @JvmStatic
        @Implementation
        fun getOrCreate(spec: KeyGenParameterSpec): String = spec.keystoreAlias
    }
}

@Implements(EncryptedSharedPreferences::class)
class TestEncryptedSharedPreferences {
    companion object {
        @JvmStatic
        @Implementation
        fun create(
            fileName: String,
            masterKeyAlias: String,
            context: Context,
            keyScheme: EncryptedSharedPreferences.PrefKeyEncryptionScheme,
            valueScheme: EncryptedSharedPreferences.PrefValueEncryptionScheme,
        ): SharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }
}
