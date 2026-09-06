package com.stog.app.feature.record

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.OutboxWorkScheduler
import com.stog.app.core.database.PendingSetLogEntity
import com.stog.app.core.database.PersistedCollectorState
import com.stog.app.core.database.SetLogOutboxState
import com.stog.app.core.database.StogDatabase
import com.stog.app.feature.auth.StoredAuthTokens
import com.stog.app.feature.space.TripSummary
import java.util.Calendar
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingCoordinatorHostTest {
    private val direct = Executor(Runnable::run)
    private lateinit var context: Context
    private lateinit var database: StogDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(direct).setTaskExecutor(direct).build(),
        )
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
        database = StogDatabase.get(context)
    }

    @After
    fun tearDown() {
        RecordingBootRuntime.reset()
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
    }

    @Test
    fun foregroundStartRejectionPersistsBlockedSchedulesPublicationAndRecoveryDoesNotRestart() {
        val token = StoredAuthTokens("access", "refresh", 7)
        val starts = mutableListOf<Intent>()
        val coordinator = RecordingActivationCoordinator(
            context,
            tokenLoader = { token },
            tripLoader = { listOf(todayTrip(41)) },
            foregroundStarter = { intent -> starts += intent; throw IllegalStateException("OS rejected") },
            dispatch = { action -> offMain(action) },
        )

        coordinator.activateCalendar()

        val state = offMain { database.memberCollectionStateDao().find("7", "41", "7") }
        assertEquals(PersistedCollectorState.BLOCKED, state?.collectorState)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
            OutboxWorkScheduler.collectionStateWorkName("7", "41"),
        ).get(10, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isNotEmpty())

        val recoveryStarts = mutableListOf<Intent>()
        RecordingActivationCoordinator(
            context,
            tokenLoader = { token },
            tripLoader = { listOf(todayTrip(41)) },
            foregroundStarter = { recoveryStarts += it },
            dispatch = { action -> offMain(action) },
        ).recover()
        assertTrue(recoveryStarts.isEmpty())
        assertTrue(offMain { database.memberCollectionStateDao().allForAccount("7") }.none {
            it.collectorState == PersistedCollectorState.STARTING || it.collectorState == PersistedCollectorState.ACTIVE
        })
        assertEquals(1, starts.size)
    }

    @Test
    fun recoverySchedulesPendingPhotoRecordsForTheActiveAccount() {
        offMain {
            database.pendingSetLogDao().enqueue(
                PendingSetLogEntity(
                    clientUploadId = "pending-photo",
                    accountId = "7",
                    userId = "7",
                    tripId = 41,
                    source = "camera",
                    originalPath = "/tmp/original.jpg",
                    thumbnailPath = "/tmp/thumbnail.jpg",
                    sourcePath = null,
                    originalSize = 1,
                    thumbnailSize = 1,
                    originalSha256 = "0".repeat(64),
                    thumbnailSha256 = "0".repeat(64),
                    latitude = null,
                    longitude = null,
                    accuracyMeters = null,
                    locationProvenance = null,
                    takenAt = null,
                    caption = null,
                    placeResolutionStatus = "no_match",
                    expectedPlaceId = null,
                    visibility = "private",
                    publicConsent = false,
                    outboxState = SetLogOutboxState.PENDING,
                    createdAt = 1,
                ),
            )
        }

        RecordingActivationCoordinator(
            context,
            tokenLoader = { StoredAuthTokens("access", "refresh", 7) },
            tripLoader = { emptyList() },
            dispatch = { action -> offMain(action) },
        ).recover()

        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
            OutboxWorkScheduler.setLogWorkName("7", 41),
        ).get(10, TimeUnit.SECONDS)
        assertEquals(1, work.size)
    }

    @Test
    fun bootReceiverHoldsAsyncCompletionUntilRecoveryFinishes() {
        var recoveryStarted = false
        var finished = false
        lateinit var complete: () -> Unit
        RecordingBootRuntime.recover = { _, callback ->
            recoveryStarted = true
            complete = callback
        }
        RecordingBootRuntime.finished = { finished = true }
        val receiver = RecordingBootReceiver()
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BOOT_COMPLETED))
        try {
            context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(recoveryStarted)
            assertFalse(finished)
            complete()
            assertTrue(finished)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun recoveryStartsEveryTripScopedSessionExactlyOnce() {
        offMain {
            database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", 1))
            listOf("trip-a", "trip-b").forEach { trip ->
                database.memberCollectionStateDao().save(
                    MemberRecordingState(
                        "7",
                        trip,
                        "7",
                        CollectorState.ACTIVE,
                        PermissionState.GRANTED,
                    ).toEntity(1),
                )
            }
        }
        val starts = mutableListOf<String>()
        RecordingActivationCoordinator(
            context,
            tokenLoader = { StoredAuthTokens("access", "refresh", 7) },
            tripLoader = { emptyList() },
            foregroundStarter = { starts += checkNotNull(it.getStringExtra("recording_trip_id")) },
            dispatch = { action -> offMain(action) },
        ).recover()

        assertEquals(listOf("trip-a", "trip-b"), starts.sorted())
        assertEquals(2, offMain { database.memberCollectionStateDao().allForAccount("7") }.count {
            it.collectorState == PersistedCollectorState.ACTIVE
        })
    }

    @Test
    fun recoveryDoesNotCrashWhenTripHasMalformedCalendarDates() {
        var completed = false
        RecordingActivationCoordinator(
            context,
            tokenLoader = { StoredAuthTokens("access", "refresh", 7) },
            tripLoader = {
                listOf(
                    TripSummary(
                        id = 41,
                        title = "broken date",
                        activityType = "walk",
                        mode = "dormant",
                        visibility = "private",
                        plannedStartDate = "2026/08/29",
                        plannedEndDate = "2026/08/29",
                    ),
                )
            },
            dispatch = { action -> offMain(action) },
        ).recover { completed = true }

        assertTrue(completed)
    }

    private fun <T> offMain(action: () -> T): T {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { action() }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun todayTrip(id: Long): TripSummary {
        val date = todayRecordingDate(Calendar.getInstance())
        val iso = "%04d-%02d-%02d".format(date.year, date.month, date.day)
        return TripSummary(id, "trip", "walk", "dormant", "private", iso, iso)
    }
}
