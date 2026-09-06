package com.stog.app.feature.record

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.OutboxWorkScheduler
import com.stog.app.core.database.PersistedCollectorState
import com.stog.app.core.database.StogDatabase
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingServiceHostTest {
    private val direct = Executor(Runnable::run)
    private lateinit var context: Context
    private lateinit var database: StogDatabase
    private lateinit var locations: FakeLocations
    private lateinit var clock: MutableClock
    private lateinit var phases: MutableList<String>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(direct).setTaskExecutor(direct).build(),
        )
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
        database = StogDatabase.get(context)
        locations = FakeLocations()
        clock = MutableClock(1_000_000)
        phases = mutableListOf()
        RecordingServiceRuntime.create = { service ->
            RecordingServiceDependencies(
                runner = BlockingRunner(),
                locations = locations,
                scheduler = OutboxWorkScheduler(service),
                now = clock::now,
                observer = object : RecordingRuntimeObserver {
                    override fun onPhase(tripId: String?, phase: String) {
                        phases += "$tripId:$phase"
                    }
                },
            )
        }
    }

    @After
    fun tearDown() {
        RecordingServiceRuntime.reset()
        RecordingBootRuntime.reset()
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
    }

    @Test
    fun foregroundPrecedesLocationAndDormantFailureNeverPublishesActive() {
        seed("trip-active", CollectorState.INACTIVE)
        val active = Robolectric.buildService(TripLocationService::class.java).create().get()
        active.onStartCommand(intent("trip-active"), 0, 1)
        assertEquals("trip-active:${TripLocationService.PHASE_FOREGROUND}", phases[0])
        assertEquals("trip-active:${TripLocationService.PHASE_LOCATION_REQUESTED}", phases[1])
        assertTrue(phases.indexOf("trip-active:publish:ACTIVE") > 1)
        active.onDestroy()

        phases.clear()
        locations = FakeLocations(failActive = true)
        seed("trip-dormant", CollectorState.DORMANT)
        val dormant = Robolectric.buildService(TripLocationService::class.java).create().get()
        dormant.onStartCommand(intent("trip-dormant"), 0, 1)
        locations.dormantListeners.single().onLocationChanged(location(clock.now()))
        assertEquals(PersistedCollectorState.BLOCKED, stored("trip-dormant").collectorState)
        assertFalse(phases.any { it == "trip-dormant:publish:ACTIVE" })
        assertTrue(phases.any { it == "trip-dormant:publish:BLOCKED" })
        dormant.onDestroy()
    }

    @Test
    fun pausedLooperTriggersDormancyAndPlannedEndWithoutLocation() {
        val dormantDelay = context.resources.getInteger(com.stog.app.R.integer.recording_dormant_after_minutes) * 60_000L
        seed("trip-dormancy", CollectorState.INACTIVE, lastTransitionAt = clock.now())
        val service = Robolectric.buildService(TripLocationService::class.java).create().get()
        service.onStartCommand(intent("trip-dormancy"), 0, 1)
        clock.value += dormantDelay
        shadowOf(Looper.getMainLooper()).idleFor(dormantDelay, TimeUnit.MILLISECONDS)
        assertEquals(PersistedCollectorState.DORMANT, stored("trip-dormancy").collectorState)
        service.onDestroy()

        seed("trip-ended", CollectorState.INACTIVE, tripEndAt = clock.now() + 1_000)
        val endedService = Robolectric.buildService(TripLocationService::class.java).create().get()
        endedService.onStartCommand(intent("trip-ended"), 0, 1)
        clock.value += 1_000
        shadowOf(Looper.getMainLooper()).idleFor(1_000, TimeUnit.MILLISECONDS)
        assertEquals(PersistedCollectorState.ENDED, stored("trip-ended").collectorState)
        endedService.onDestroy()
    }

    @Test
    fun batteryLatchBlocksMotionUntilChargingAndReceiverIsRemovedAtTeardown() {
        seed("trip-battery", CollectorState.INACTIVE)
        val service = Robolectric.buildService(TripLocationService::class.java).create().get()
        service.onStartCommand(intent("trip-battery"), 0, 1)
        context.sendBroadcast(battery(15))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PersistedCollectorState.DORMANT, stored("trip-battery").collectorState)
        assertTrue(stored("trip-battery").batteryLow)

        val dormant = locations.dormantListeners.single()
        dormant.onLocationChanged(location(clock.now() + 1))
        assertEquals(PersistedCollectorState.DORMANT, stored("trip-battery").collectorState)
        assertEquals(1, locations.activeRequests)

        context.sendBroadcast(battery(16))
        shadowOf(Looper.getMainLooper()).idle()
        dormant.onLocationChanged(location(clock.now() + 2))
        assertEquals(PersistedCollectorState.ACTIVE, stored("trip-battery").collectorState)
        assertEquals(2, locations.activeRequests)

        val application = shadowOf(context as Application)
        val receiverCount = application.registeredReceivers.size
        assertTrue(receiverCount > 0)
        service.onDestroy()
        assertEquals(receiverCount - 1, application.registeredReceivers.size)
        assertTrue(locations.removed.isNotEmpty())
    }

    @Test
    fun livePermissionRevokeBlocksOnlyAddressedSessionAndOverlappingTripsRemainLive() {
        seed("trip-a", CollectorState.INACTIVE)
        seed("trip-b", CollectorState.INACTIVE)
        val service = Robolectric.buildService(TripLocationService::class.java).create().get()
        service.onStartCommand(intent("trip-a"), 0, 1)
        service.onStartCommand(intent("trip-b"), 0, 2)
        assertEquals(setOf("trip-a", "trip-b"), service.activeTripIdsForHost())
        assertEquals(2, locations.activeListeners.size)

        shadowOf(context as Application).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        context.sendBroadcast(battery(80))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PersistedCollectorState.BLOCKED, stored("trip-a").collectorState)
        assertEquals(PersistedCollectorState.BLOCKED, stored("trip-b").collectorState)
        assertTrue(service.activeTripIdsForHost().isEmpty())
        service.onDestroy()
    }

    private fun seed(
        tripId: String,
        state: CollectorState,
        lastTransitionAt: Long? = null,
        tripEndAt: Long? = null,
    ) {
        offMain {
            if (database.accountOwnershipDao().find("7") == null) {
                database.accountOwnershipDao().insert(AccountOwnershipEntity("7", "7", clock.now()))
            }
            database.memberCollectionStateDao().save(
                MemberRecordingState(
                    accountId = "7",
                    tripId = tripId,
                    userId = "7",
                    collectorState = state,
                    permissionState = PermissionState.GRANTED,
                    tripEndAt = tripEndAt,
                    lastTransitionAt = lastTransitionAt,
                ).toEntity(clock.now()),
            )
        }
    }

    private fun stored(tripId: String) = offMain {
        checkNotNull(database.memberCollectionStateDao().find("7", tripId, "7"))
    }

    private fun intent(tripId: String) = TripLocationService.intent(context, tripId, "7", "token", null)

    private fun location(time: Long) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 35.8
        longitude = 127.1
        this.time = time
    }

    private fun battery(percent: Int) = Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, percent)
        .putExtra(BatteryManager.EXTRA_SCALE, 100)

    private class BlockingRunner : RecordingTaskRunner {
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        override fun execute(action: () -> Unit) {
            executor.submit(action).get(10, TimeUnit.SECONDS)
        }
        override fun close() {
            executor.shutdownNow()
        }
    }

    private fun <T> offMain(action: () -> T): T {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            executor.submit<T> { action() }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private class MutableClock(var value: Long) {
        fun now() = value
    }

    private class FakeLocations(private val failActive: Boolean = false) : LocationCollection {
        val activeListeners = mutableListOf<LocationListener>()
        val dormantListeners = mutableListOf<LocationListener>()
        val removed = mutableListOf<LocationListener>()
        var activeRequests = 0

        override fun requestActive(listener: LocationListener, distanceMeters: Float) {
            activeRequests++
            if (failActive) throw IllegalStateException("OS blocked")
            activeListeners += listener
        }

        override fun requestDormant(listener: LocationListener, distanceMeters: Float) {
            dormantListeners += listener
        }

        override fun remove(listener: LocationListener) {
            removed += listener
            activeListeners.remove(listener)
            dormantListeners.remove(listener)
        }
    }
}
