package com.stog.app.feature.record

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.LocationObservationEntity
import com.stog.app.core.database.OutboxState
import com.stog.app.core.database.ShareImportOutboxTransport
import com.stog.app.core.database.StogDatabase
import com.stog.app.core.database.TransmissionOutcome
import com.stog.app.core.database.TypedOutboxProcessor
import com.stog.app.core.database.VisitOutboxEntity
import com.stog.app.core.database.VisitOutboxTransport
import com.stog.app.core.database.VisitReviewReceipt
import com.stog.app.core.database.VisitStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReviewReceiptPersistenceTest {
    private lateinit var database: StogDatabase

    @Before
    fun setUp() {
        database = StogDatabase.inMemory(ApplicationProvider.getApplicationContext())
        database.accountOwnershipDao().insert(AccountOwnershipEntity("account", "user", 1))
        database.locationObservationDao().insert(
            LocationObservationEntity("observation", "account", "trip", "user", 10, 35.8, 127.1, 1),
        )
        database.visitOutboxDao().enqueue(
            VisitOutboxEntity(
                observationId = "observation",
                accountId = "account",
                tripId = "trip",
                userId = "user",
                clientVisitId = "client",
                payloadFingerprint = "fingerprint",
                cellId = 10,
                lat = 35.8,
                lng = 127.1,
                enteredAt = 1,
                leftAt = 2,
                status = VisitStatus.VISITED,
                isInterpolated = false,
                createdAt = 3,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun acknowledgedReviewReceiptIsDurableAndConsumedOnce() = runBlocking {
        val processor = TypedOutboxProcessor(
            database,
            VisitOutboxTransport {
                TransmissionOutcome.VisitAcknowledged(VisitReviewReceipt(77, "client", true))
            },
            ShareImportOutboxTransport { _, _ -> error("not used") },
        )

        assertEquals(false, processor.processVisits("account", "trip"))
        val pending = database.visitOutboxDao().observePendingReview("account").first()
        assertEquals(77L, pending?.remoteVisitId)
        assertEquals(OutboxState.ACKNOWLEDGED, pending?.outboxState)
        assertEquals(1, database.visitOutboxDao().consumeReview(checkNotNull(pending).id, 10))
        assertNull(database.visitOutboxDao().observePendingReview("account").first())
        assertEquals(0, database.visitOutboxDao().consumeReview(pending.id, 11))
    }

    @Test
    fun falseReceiptAcknowledgesWithoutOpeningReview() = runBlocking {
        val processor = TypedOutboxProcessor(
            database,
            VisitOutboxTransport {
                TransmissionOutcome.VisitAcknowledged(VisitReviewReceipt(78, "client", false))
            },
            ShareImportOutboxTransport { _, _ -> error("not used") },
        )

        processor.processVisits("account", "trip")

        assertNull(database.visitOutboxDao().observePendingReview("account").first())
        assertEquals(false, database.visitOutboxDao().find(1)?.reviewRequired)
    }
}
