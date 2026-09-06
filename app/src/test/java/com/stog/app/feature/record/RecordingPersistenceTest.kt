package com.stog.app.feature.record

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.OutboxRetryPolicy
import com.stog.app.core.database.OutboxState
import com.stog.app.core.database.PersistedCollectorState
import com.stog.app.core.database.StogDatabase
import com.stog.app.core.database.TransmissionOutcome
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecordingPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: StogDatabase
    private val tuning = RecordingTuning(50.0, 2, 3_600_000, 1_800_000, 15)

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
    fun confirmedTransitionAtomicallyCreatesImmutableVisitAndSchedulesMemberScope() {
        database.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "member-a", 1))
        val scheduled = mutableListOf<Pair<String, String>>()
        val engine = RecordingEngine(database, { account, trip -> scheduled += account to trip }, tuning) { 4_000 }
        var state = member("account-a", "member-a")

        state = engine.handle(state, RecordingEvent.Location(10, 35.0, 127.0, 0)).state
        state = engine.handle(state, RecordingEvent.Location(11, 35.001, 127.0, 2_000)).state
        state = engine.handle(state, RecordingEvent.Location(11, 35.002, 127.0, 3_000)).state

        val rows = database.visitOutboxDao().allForAccount("account-a")
        assertEquals(1, rows.size)
        assertEquals(10L, rows.single().cellId)
        assertEquals(OutboxState.PENDING, rows.single().outboxState)
        assertEquals(listOf("account-a" to "trip-a"), scheduled)
        assertEquals(11L, database.memberCollectionStateDao().find("account-a", "trip-a", "member-a")?.activeCell)
        assertTrue(rows.single().payloadFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun crashAfterAtomicCommitBeforeWorkSchedulingRetainsRecoverableVisit() {
        database.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "member-a", 1))
        val engine = RecordingEngine(database, { _, _ -> error("crash after commit") }, tuning) { 4_000 }
        var state = member("account-a", "member-a")
        state = engine.handle(state, RecordingEvent.Location(10, 35.0, 127.0, 0)).state
        state = engine.handle(state, RecordingEvent.Location(11, 35.001, 127.0, 2_000)).state

        val failure = runCatching {
            engine.handle(state, RecordingEvent.Location(11, 35.002, 127.0, 3_000))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("trip-a"), database.visitOutboxDao().pendingTripIds("account-a"))
        assertEquals(11L, database.memberCollectionStateDao().find("account-a", "trip-a", "member-a")?.activeCell)
    }

    @Test
    fun persistedMemberStateSurvivesProcessRecreationAndAccountsStayIsolated() {
        database.close()
        val name = "recording-${UUID.randomUUID()}.db"
        val first = StogDatabase.build(context, name)
        offMain {
            first.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "member-a", 1))
            first.accountOwnershipDao().insert(AccountOwnershipEntity("account-b", "member-b", 1))
            first.memberCollectionStateDao().save(member("account-a", "member-a").copy(tripEndAt = 1234).toEntity(1))
            first.memberCollectionStateDao().save(
                member("account-b", "member-b").copy(collectorState = CollectorState.DORMANT).toEntity(1),
            )
        }
        first.close()

        val recreated = StogDatabase.build(context, name)
        val accountA = offMain { recreated.memberCollectionStateDao().allForAccount("account-a") }
        val accountB = offMain { recreated.memberCollectionStateDao().allForAccount("account-b") }
        recreated.close()
        context.deleteDatabase(name)

        assertEquals(listOf("member-a"), accountA.map { it.userId })
        assertEquals(PersistedCollectorState.ACTIVE, accountA.single().collectorState)
        assertEquals(1234L, accountA.single().tripEndAt)
        assertEquals(listOf("member-b"), accountB.map { it.userId })
        assertNotEquals(accountA.single().accountId, accountB.single().accountId)
        database = StogDatabase.inMemory(context)
    }

    @Test
    fun roomVersionOneUpgradesToPersistedMemberRecoveryState() {
        database.close()
        val name = "recording-migration-${UUID.randomUUID()}.db"
        val schemaFile = sequenceOf(
            File("schemas/com.stog.app.core.database.StogDatabase/1.json"),
            File("app/schemas/com.stog.app.core.database.StogDatabase/1.json"),
        ).first(File::isFile)
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
            }
            repeat(entities.length()) { index ->
                val indices = entities.getJSONObject(index).getJSONArray("indices")
                repeat(indices.length()) { item ->
                    val definition = indices.getJSONObject(item)
                    sqlite.execSQL(
                        definition.getString("createSql")
                            .replace("${'$'}{TABLE_NAME}", entities.getJSONObject(index).getString("tableName")),
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { sqlite.execSQL(setup.getString(it)) }
            sqlite.execSQL("INSERT INTO account_ownership VALUES ('seed-account','seed-user',1)")
            sqlite.execSQL("INSERT INTO location_observations VALUES ('seed-observation','seed-account','seed-trip','seed-user',1,35.8,127.1,1,'CLASSIFIED')")
            sqlite.execSQL("INSERT INTO visit_outbox VALUES (1,'seed-observation','seed-account','seed-trip','seed-user','seed-client','seed-fingerprint',1,35.8,127.1,1,2,'VISITED',0,'ACKNOWLEDGED','NONE',1,200,1)")
            sqlite.execSQL("INSERT INTO pending_share_imports VALUES ('seed-import','seed-account','seed-dir','raw',NULL,'text/plain','UNKNOWN','CLASSIFIED',1)")
            sqlite.execSQL("INSERT INTO share_import_candidates VALUES ('seed-candidate','seed-import','EXTRACTED','place',NULL,NULL,0)")
            sqlite.execSQL("INSERT INTO share_import_decisions VALUES ('seed-candidate','seed-import','CONFIRMED','ACKNOWLEDGED','NONE',1,200,1)")
            sqlite.version = 1
        }

        val upgraded = StogDatabase.build(context, name)
        offMain {
            upgraded.accountOwnershipDao().insert(AccountOwnershipEntity("account-a", "member-a", 1))
            upgraded.memberCollectionStateDao().save(member("account-a", "member-a").toEntity(2))
        }
        assertEquals(
            PersistedCollectorState.ACTIVE,
            offMain { upgraded.memberCollectionStateDao().find("account-a", "trip-a", "member-a")?.collectorState },
        )
        listOf(
            "account_ownership" to 2,
            "location_observations" to 1,
            "visit_outbox" to 1,
            "pending_share_imports" to 1,
            "share_import_candidates" to 1,
            "share_import_decisions" to 1,
        ).forEach { (table, expected) ->
            assertEquals(expected, offMain {
                upgraded.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            })
        }
        assertEquals(1, offMain {
            upgraded.query("SELECT COUNT(*) FROM member_collection_states", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        })
        upgraded.close()
        context.deleteDatabase(name)
        database = StogDatabase.inMemory(context)
    }

    @Test
    fun visitRetryClassesRemainTodoFiveTypedContract() {
        assertTrue(OutboxRetryPolicy.classify(TransmissionOutcome.NetworkFailure).shouldRetry)
        assertTrue(OutboxRetryPolicy.classify(TransmissionOutcome.HttpFailure(429)).shouldRetry)
        assertTrue(OutboxRetryPolicy.classify(TransmissionOutcome.HttpFailure(503)).shouldRetry)
        assertEquals(OutboxState.TERMINAL, OutboxRetryPolicy.classify(TransmissionOutcome.HttpFailure(409)).state)
    }

    private fun member(accountId: String, userId: String) = MemberRecordingState(
        accountId = accountId,
        tripId = "trip-a",
        userId = userId,
        collectorState = CollectorState.ACTIVE,
        permissionState = PermissionState.GRANTED,
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
