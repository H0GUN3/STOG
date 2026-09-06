package com.stog.app.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
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
class StogDatabaseIntegrityTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var database: StogDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "integrity-${UUID.randomUUID()}.db"
        database = StogDatabase.build(context, name)
        offMain { database.openHelper.writableDatabase }
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test
    fun compositeOwnershipRejectsCrossAccountUserAndCrossImportCandidatePoisoning() {
        offMain {
            database.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "user-a", 1))
            database.accountOwnershipDao().insert(AccountOwnershipEntity("account-b", "user-b", 2))
        }

        val crossAccount = sqlFailure(
            """INSERT INTO location_observations
               (observation_id,account_id,trip_id,user_id,cell_id,lat,lng,observed_at,classification_state)
               VALUES ('poison-observation','account-a','trip-a','user-b',1,35.8,127.1,1,'PENDING')""".trimIndent(),
        )
        assertNotNull(crossAccount)
        assertNull(offMain { database.locationObservationDao().find("poison-observation") })

        offMain { database.locationObservationDao().insert(observation()) }
        val crossAccountVisit = sqlFailure(
            """INSERT INTO visit_outbox
               (observation_id,account_id,trip_id,user_id,client_visit_id,payload_fingerprint,cell_id,lat,lng,
                entered_at,left_at,status,is_interpolated,outbox_state,retry_class,attempt_count,last_response_code,created_at)
               VALUES ('observation-a','account-a','trip-a','user-b','poison-client','fingerprint',1,35.8,127.1,
                1,2,'VISITED',0,'PENDING','NONE',0,NULL,1)""".trimIndent(),
        )
        assertNotNull(crossAccountVisit)
        assertNull(offMain { database.visitOutboxDao().next("account-a", "trip-a") })

        offMain {
            database.shareImportDao().insertPending(pending("import-a", "account-a", 1))
            database.shareImportDao().insertPending(pending("import-b", "account-b", 2))
            database.shareImportDao().persistCandidates("import-a", listOf(candidate("import-a", "candidate-a")))
            database.shareImportDao().persistCandidates("import-b", listOf(candidate("import-b", "candidate-b")))
        }
        val crossImport = sqlFailure(
            """INSERT INTO share_import_decisions
               (candidate_id,import_id,decision,sync_state,retry_class,attempt_count,last_response_code,decided_at)
               VALUES ('candidate-a','import-b','CONFIRMED','PENDING','NONE',0,NULL,3)""".trimIndent(),
        )
        assertNotNull(crossImport)

        offMain {
            database.shareImportDao().insertDecision(
                ShareImportDecisionEntity("candidate-b", "import-b", CandidateDecisionState.CONFIRMED, decidedAt = 3),
            )
        }
        val sent = mutableListOf<String>()
        val shouldRetry = offMain {
            TypedOutboxProcessor(
                database,
                { error("not used") },
                { decision, candidate ->
                    sent += "${decision.importId}:${candidate.candidateId}"
                    TransmissionOutcome.Acknowledged
                },
            ).processShareImports("account-b")
        }
        assertEquals(false, shouldRetry)
        assertEquals(listOf("import-b:candidate-b"), sent)
    }

    @Test
    fun everyImmutableIdentityRejectsDirectSqlChangedKeyUpdates() {
        offMain {
            database.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "user-a", 1))
            database.accountOwnershipDao().insert(AccountOwnershipEntity("account-b", "user-b", 2))
            database.locationObservationDao().insert(observation())
            database.visitOutboxDao().enqueue(visit())
            database.pendingSetLogDao().insert(pendingSetLog())
            database.shareImportDao().insertPending(pending("import-a", "account-a", 1))
            database.shareImportDao().persistCandidates("import-a", listOf(candidate("import-a", "candidate-a")))
            database.shareImportDao().insertDecision(
                ShareImportDecisionEntity("candidate-a", "import-a", CandidateDecisionState.CONFIRMED, decidedAt = 2),
            )
        }

        val changedKeys = listOf(
            "UPDATE account_ownership SET account_id='account-changed' WHERE account_id='account-a'",
            "UPDATE location_observations SET observation_id='observation-changed' WHERE observation_id='observation-a'",
            "UPDATE visit_outbox SET id=99 WHERE client_visit_id='client-a'",
            "UPDATE pending_set_logs SET trip_id=99 WHERE client_upload_id='set-log-a'",
            "UPDATE pending_share_imports SET import_id='import-changed' WHERE import_id='import-a'",
            "UPDATE pending_share_imports SET account_id='account-b' WHERE import_id='import-a'",
            "UPDATE share_import_candidates SET candidate_id='candidate-changed' WHERE candidate_id='candidate-a'",
            "UPDATE share_import_decisions SET candidate_id='candidate-changed' WHERE candidate_id='candidate-a'",
        )
        changedKeys.forEach { statement ->
            assertNotNull("Expected immutable-key abort for $statement", sqlFailure(statement))
        }

        assertEquals("account-a", offMain { database.accountOwnershipDao().find("account-a")?.accountId })
        assertEquals("observation-a", offMain { database.locationObservationDao().find("observation-a")?.observationId })
        assertEquals("candidate-a", offMain { database.shareImportDao().nextConfirmed("account-a")?.candidateId })
    }

    private fun sqlFailure(sql: String): Throwable? = runCatching {
        offMain { database.openHelper.writableDatabase.execSQL(sql) }
    }.exceptionOrNull()

    private fun pendingSetLog() = PendingSetLogEntity(
        "set-log-a", "account-a", "user-a", 9, "camera", "/owned/original", "/owned/thumbnail", null,
        10, 5, "a".repeat(64), "b".repeat(64), null, null, null, null,
        "2026-08-25T00:00:00Z", null, "no_match", null, "private", false, createdAt = 1,
    )

    private fun pending(id: String, account: String, time: Long) =
        PendingShareImportEntity(id, account, id, "raw", null, "text/plain", "UNKNOWN", createdAt = time)

    private fun candidate(importId: String, id: String) =
        ShareImportCandidateEntity(id, importId, CandidateOrigin.EXTRACTED, "장소", null, null, 0)

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
