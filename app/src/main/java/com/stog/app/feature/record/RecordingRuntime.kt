package com.stog.app.feature.record

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stog.app.BuildConfig
import com.stog.app.MainActivity
import com.stog.app.R
import com.stog.app.core.database.AccountOwnershipEntity
import com.stog.app.core.database.OutboxWorkScheduler
import com.stog.app.core.database.PendingSetLogWorkInitializer
import com.stog.app.core.database.PersistedCollectorState
import com.stog.app.core.database.StogDatabase
import com.stog.app.feature.auth.AuthTokenStore
import com.stog.app.feature.auth.StoredAuthTokens
import com.stog.app.feature.space.PlanningApiClient
import com.stog.app.feature.space.TripSummary
import com.uber.h3core.H3Core
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val RECORDING_LOCATION_LOG_TAG = "STOG.RecordingLocation"

private fun recordingLocationLog(message: String) {
    if (!BuildConfig.DEBUG) return
    try {
        Log.d(RECORDING_LOCATION_LOG_TAG, message)
    } catch (_: RuntimeException) {
        // Host JVM tests do not provide Android's Log implementation.
    }
}

internal fun recordingTuning(context: Context) = RecordingTuning(
    locationDistanceFilterMeters = context.resources.getInteger(R.integer.recording_location_distance_filter_m).toDouble(),
    cellTransitionConfirm = context.resources.getInteger(R.integer.recording_cell_transition_confirm),
    dormantAfterMillis = context.resources.getInteger(R.integer.recording_dormant_after_minutes) * 60_000L,
    visitDwellMillis = context.resources.getInteger(R.integer.recording_visit_dwell_minutes) * 60_000L,
    batteryCutoffPercent = context.resources.getInteger(R.integer.recording_battery_cutoff_percent),
)

internal fun todayRecordingDate(calendar: Calendar = Calendar.getInstance()) = RecordingDate(
    calendar.get(Calendar.YEAR),
    calendar.get(Calendar.MONTH) + 1,
    calendar.get(Calendar.DAY_OF_MONTH),
)

internal fun endAfterDate(value: String): Long {
    val date = RecordingDate.parse(value)
    return Calendar.getInstance().apply {
        clear()
        set(date.year, date.month - 1, date.day)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
}

internal fun collectionPermissionState(context: Context, previouslyGranted: Boolean): PermissionState {
    val foregroundGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return when {
        foregroundGranted && backgroundGranted -> PermissionState.GRANTED
        previouslyGranted -> PermissionState.REVOKED
        else -> PermissionState.DENIED
    }
}

private fun asyncDispatch(action: () -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    executor.execute {
        try {
            action()
        } finally {
            executor.shutdown()
        }
    }
}

class RecordingActivationCoordinator(
    private val context: Context,
    private val tokenLoader: () -> StoredAuthTokens? = {
        runCatching { AuthTokenStore(context).load() }.getOrNull()
    },
    private val tripLoader: (String) -> List<TripSummary> = { token ->
        PlanningApiClient(BuildConfig.STOG_API_BASE_URL).listTrips(token)
    },
    private val foregroundStarter: (Intent) -> Unit = { intent ->
        ContextCompat.startForegroundService(context, intent)
    },
    private val dispatch: ((() -> Unit) -> Unit) = ::asyncDispatch,
) {
    fun activateCalendar() = executeActivation(ActivationTrigger.Calendar(todayRecordingDate()))

    fun activateDestination(tripId: String) = executeActivation(ActivationTrigger.Destination(tripId))

    fun recover(onComplete: () -> Unit = {}) {
        val tokens = tokenLoader()
        if (tokens == null) {
            onComplete()
            return
        }
        dispatch {
            try {
                val accountId = tokens.userId.toString()
                val database = StogDatabase.get(context)
                database.visitOutboxDao().pendingTripIds(accountId).forEach { tripId ->
                    OutboxWorkScheduler(context).enqueueVisits(accountId, tripId)
                }
                PendingSetLogWorkInitializer.schedule(context, accountId)
                database.memberCollectionStateDao().allForAccount(accountId).forEach { state ->
                    when (state.collectorState) {
                        PersistedCollectorState.STARTING,
                        PersistedCollectorState.ACTIVE,
                        PersistedCollectorState.DORMANT -> startCollector(
                            state.tripId,
                            state.userId,
                            tokens.accessToken,
                            null,
                        )
                        PersistedCollectorState.BLOCKED ->
                            OutboxWorkScheduler(context).enqueueCollectionState(accountId, state.tripId)
                        else -> Unit
                    }
                }
                activateTrips(tokens, ActivationTrigger.Calendar(todayRecordingDate()))
            } finally {
                onComplete()
            }
        }
    }

    private fun executeActivation(trigger: ActivationTrigger) {
        val tokens = tokenLoader() ?: return
        dispatch { activateTrips(tokens, trigger) }
    }

    private fun activateTrips(tokens: StoredAuthTokens, trigger: ActivationTrigger) {
        val trips = runCatching { tripLoader(tokens.accessToken) }.getOrElse { return }
        val accountId = tokens.userId.toString()
        val recordingTrips = trips.mapNotNull { trip ->
            runCatching { trip.toRecordingTrip() }
                .onFailure {
                    recordingLocationLog("Ignoring trip id=${trip.id}: malformed planned date")
                }
                .getOrNull()
        }
        tripsForActivation(recordingTrips, trigger).forEach { selected ->
            val trip = trips.single { it.id.toString() == selected.tripId }
            val existing = StogDatabase.get(context).memberCollectionStateDao()
                .find(accountId, selected.tripId, accountId)
            if (existing?.collectorState !in setOf(
                    PersistedCollectorState.STARTING,
                    PersistedCollectorState.ACTIVE,
                    PersistedCollectorState.DORMANT,
                    PersistedCollectorState.BLOCKED,
                )
            ) {
                prepareMember(accountId, selected.tripId, trip.plannedEndDate)
                startCollector(selected.tripId, accountId, tokens.accessToken, trip.plannedEndDate)
            }
        }
    }

    private fun prepareMember(accountId: String, tripId: String, plannedEndDate: String?) {
        val database = StogDatabase.get(context)
        if (database.accountOwnershipDao().find(accountId) == null) {
            database.accountOwnershipDao().insert(AccountOwnershipEntity(accountId, accountId, System.currentTimeMillis()))
        }
        val existing = database.memberCollectionStateDao().find(accountId, tripId, accountId)
        val endAt = plannedEndDate?.let(::endAfterDate) ?: existing?.tripEndAt
        database.memberCollectionStateDao().save(
            existing?.copy(tripEndAt = endAt, updatedAt = System.currentTimeMillis())
                ?: MemberRecordingState(
                    accountId = accountId,
                    tripId = tripId,
                    userId = accountId,
                    collectorState = CollectorState.INACTIVE,
                    permissionState = PermissionState.UNKNOWN,
                    tripEndAt = endAt,
                ).toEntity(System.currentTimeMillis()),
        )
    }

    private fun startCollector(
        tripId: String,
        userId: String,
        accessToken: String,
        plannedEndDate: String?,
    ) {
        val intent = TripLocationService.intent(context, tripId, userId, accessToken, plannedEndDate)
        runCatching { foregroundStarter(intent) }.onFailure {
            val database = StogDatabase.get(context)
            val current = database.memberCollectionStateDao().find(userId, tripId, userId)
            if (current != null) {
                database.memberCollectionStateDao().save(
                    current.copy(
                        collectorState = PersistedCollectorState.BLOCKED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                OutboxWorkScheduler(context).enqueueCollectionState(userId, tripId)
            }
            RecordingNotifications(context).actionRequired(FailureReason.OS_BLOCK)
        }
    }
}

private fun TripSummary.toRecordingTrip() = RecordingTrip(
    tripId = id.toString(),
    plannedStartDate = plannedStartDate?.let(RecordingDate::parse),
    plannedEndDate = plannedEndDate?.let(RecordingDate::parse),
    ended = mode == "ended",
)

internal interface LocationCollection {
    fun requestActive(listener: LocationListener, distanceMeters: Float)
    fun requestDormant(listener: LocationListener, distanceMeters: Float)
    fun remove(listener: LocationListener)
}

private class AndroidLocationCollection(
    private val service: Service,
    private val manager: LocationManager,
) : LocationCollection {
    override fun requestActive(listener: LocationListener, distanceMeters: Float) {
        checkPermission()
        manager.removeUpdates(listener)
        val providers = activeProviders()
        recordingLocationLog("request active providers=${providers.joinToString()}")
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, 0L, distanceMeters, listener, Looper.getMainLooper())
        }
    }

    override fun requestDormant(listener: LocationListener, distanceMeters: Float) {
        checkPermission()
        manager.removeUpdates(listener)
        manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, distanceMeters, listener, Looper.getMainLooper())
    }

    override fun remove(listener: LocationListener) = manager.removeUpdates(listener)

    private fun activeProviders(): List<String> = if (
        ContextCompat.checkSelfPermission(
            service,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    } else {
        listOf(LocationManager.NETWORK_PROVIDER)
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Location permission is unavailable")
        }
    }
}

internal interface RecordingTaskRunner {
    fun execute(action: () -> Unit)
    fun close()
}

private class ExecutorRecordingTaskRunner : RecordingTaskRunner {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    override fun execute(action: () -> Unit) = executor.execute(action)
    override fun close() {
        executor.shutdownNow()
    }
}

internal interface RecordingRuntimeObserver {
    fun onPhase(tripId: String?, phase: String)
}

private object NoOpRecordingRuntimeObserver : RecordingRuntimeObserver {
    override fun onPhase(tripId: String?, phase: String) = Unit
}

internal data class RecordingServiceDependencies(
    val runner: RecordingTaskRunner,
    val locations: LocationCollection,
    val scheduler: OutboxWorkScheduler,
    val now: () -> Long,
    val observer: RecordingRuntimeObserver,
)

internal object RecordingServiceRuntime {
    var create: (TripLocationService) -> RecordingServiceDependencies = { service ->
        RecordingServiceDependencies(
            runner = ExecutorRecordingTaskRunner(),
            locations = AndroidLocationCollection(service, service.getSystemService(LocationManager::class.java)),
            scheduler = OutboxWorkScheduler(service),
            now = System::currentTimeMillis,
            observer = NoOpRecordingRuntimeObserver,
        )
    }

    fun reset() {
        create = { service ->
            RecordingServiceDependencies(
                runner = ExecutorRecordingTaskRunner(),
                locations = AndroidLocationCollection(service, service.getSystemService(LocationManager::class.java)),
                scheduler = OutboxWorkScheduler(service),
                now = System::currentTimeMillis,
                observer = NoOpRecordingRuntimeObserver,
            )
        }
    }
}

class TripLocationService : Service() {
    private lateinit var dependencies: RecordingServiceDependencies
    private lateinit var notifications: RecordingNotifications
    private lateinit var engine: RecordingEngine
    private var h3: H3Core? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = linkedMapOf<String, RuntimeSession>()
    private var batteryReceiverRegistered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val percent = intent?.batteryPercent() ?: return
            dependencies.runner.execute { onBatteryChanged(percent) }
        }
    }

    private inner class RuntimeSession(
        var state: MemberRecordingState,
        val accessToken: String,
        val listener: LocationListener,
    ) {
        val timeCheck = Runnable { dependencies.runner.execute { onTimeChanged(this) } }
    }

    override fun onCreate() {
        super.onCreate()
        dependencies = RecordingServiceRuntime.create(this)
        notifications = RecordingNotifications(this)
        h3 = runCatching { H3Core.newInstance() }.getOrNull()
        engine = RecordingEngine(
            StogDatabase.get(this),
            { accountId, tripId -> dependencies.scheduler.enqueueVisits(accountId, tripId) },
            recordingTuning(this),
            dependencies.now,
        )
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tripId = intent?.getStringExtra(EXTRA_TRIP_ID) ?: return START_NOT_STICKY
        val userId = intent.getStringExtra(EXTRA_USER_ID) ?: return START_NOT_STICKY
        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: return START_NOT_STICKY
        val endAt = intent.getStringExtra(EXTRA_PLANNED_END_DATE)?.let(::endAfterDate)
        startForeground(RecordingNotifications.COLLECTION_NOTIFICATION_ID, notifications.collectionActive())
        dependencies.observer.onPhase(tripId, PHASE_FOREGROUND)
        dependencies.runner.execute { startSession(userId, tripId, accessToken, endAt) }
        return START_STICKY
    }

    private fun startSession(userId: String, tripId: String, accessToken: String, endAt: Long?) {
        val stored = StogDatabase.get(this).memberCollectionStateDao().find(userId, tripId, userId)
            ?: return persistMissingBlocked(userId, tripId)
        sessions.remove(tripId)?.let(::removeSession)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                recordingLocationLog("callback provider=${location.provider ?: "unknown"}")
                dependencies.runner.execute { sessions[tripId]?.let { onLocation(it, location) } }
            }

            override fun onProviderDisabled(provider: String) {
                recordingLocationLog("provider disabled=$provider")
                if (provider == LocationManager.GPS_PROVIDER) {
                    dependencies.runner.execute { sessions[tripId]?.let { failClosed(it, FailureReason.OS_BLOCK) } }
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val session = RuntimeSession(
            stored.toRecordingState().copy(tripEndAt = endAt ?: stored.tripEndAt),
            accessToken,
            listener,
        )
        sessions[tripId] = session
        when (session.state.collectorState) {
            CollectorState.BLOCKED, CollectorState.ENDED -> removeSession(session)
            CollectorState.DORMANT -> requestDormant(session)
            else -> startActive(session)
        }
        currentBatteryPercent()?.let { onBatteryChanged(it) }
        if (sessions[tripId] === session) scheduleTimeCheck(session)
    }

    private fun startActive(session: RuntimeSession) {
        session.state = engine.handle(session.state, RecordingEvent.CollectorStartRequested).state
        if (session.state.collectorState != CollectorState.STARTING) return
        val permission = collectionPermissionState(this, session.state.permissionState == PermissionState.GRANTED)
        if (permission != PermissionState.GRANTED) {
            applyResult(session, engine.handle(session.state, RecordingEvent.PermissionChanged(permission)))
            return
        }
        try {
            dependencies.locations.requestActive(session.listener, recordingTuning(this).locationDistanceFilterMeters.toFloat())
            dependencies.observer.onPhase(session.state.tripId, PHASE_LOCATION_REQUESTED)
            applyResult(session, engine.handle(session.state, RecordingEvent.CollectorStartCompleted(true)))
        } catch (_: SecurityException) {
            applyResult(session, engine.handle(session.state, RecordingEvent.PermissionChanged(PermissionState.REVOKED)))
        } catch (_: RuntimeException) {
            applyResult(session, engine.handle(session.state, RecordingEvent.OsBlocked))
        }
    }

    private fun requestDormant(session: RuntimeSession) {
        runCatching {
            dependencies.locations.requestDormant(session.listener, recordingTuning(this).locationDistanceFilterMeters.toFloat())
        }.onFailure { failClosed(session, FailureReason.OS_BLOCK) }
    }

    private fun onLocation(session: RuntimeSession, location: Location) {
        if (collectionPermissionState(this, session.state.permissionState == PermissionState.GRANTED) != PermissionState.GRANTED) {
            applyResult(session, engine.handle(session.state, RecordingEvent.PermissionChanged(PermissionState.REVOKED)))
            return
        }
        if (session.state.collectorState == CollectorState.DORMANT) {
            if (session.state.batteryLow) return
            session.state = engine.handle(session.state, RecordingEvent.MotionDetected).state
            if (session.state.collectorState != CollectorState.STARTING) return
            try {
                dependencies.locations.requestActive(session.listener, recordingTuning(this).locationDistanceFilterMeters.toFloat())
                dependencies.observer.onPhase(session.state.tripId, PHASE_LOCATION_REQUESTED)
                applyResult(session, engine.handle(session.state, RecordingEvent.CollectorStartCompleted(true)))
            } catch (_: SecurityException) {
                applyResult(session, engine.handle(session.state, RecordingEvent.PermissionChanged(PermissionState.REVOKED)))
            } catch (_: RuntimeException) {
                applyResult(session, engine.handle(session.state, RecordingEvent.OsBlocked))
            }
            return
        }
        val observedAt = location.time.takeIf { it > 0 } ?: dependencies.now()
        if (session.state.tripEndAt?.let { observedAt >= it } == true) {
            applyResult(session, engine.handle(session.state, RecordingEvent.TripEnded(observedAt)))
            return
        }
        SetLogRecentLocationCache.put(
            accountId = session.state.accountId,
            tripId = session.state.tripId,
            userId = session.state.userId,
            location = SetLogRecentLocation(
                provider = location.provider ?: LocationManager.NETWORK_PROVIDER,
                observation = SetLogLocationObservation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.toDouble(),
                    sourceTimeMillis = observedAt,
                    sourceElapsedRealtimeNanos = location.elapsedRealtimeNanos.takeIf { it > 0L },
                ),
            ),
        )
        val h3Engine = h3 ?: run {
            applyResult(session, engine.handle(session.state, RecordingEvent.OsBlocked))
            return
        }
        applyResult(
            session,
            engine.handle(
                session.state,
                RecordingEvent.Location(
                    cell = h3Engine.latLngToCell(
                        location.latitude,
                        location.longitude,
                        resources.getInteger(R.integer.recording_cell_resolution),
                    ),
                    lat = location.latitude,
                    lng = location.longitude,
                    observedAt = observedAt,
                ),
            ),
        )
        if (sessions[session.state.tripId] === session && session.state.collectorState == CollectorState.ACTIVE) {
            scheduleTimeCheck(session)
        }
    }

    private fun onBatteryChanged(percent: Int) {
        sessions.values.toList().forEach { session ->
            val permission = collectionPermissionState(this, session.state.permissionState == PermissionState.GRANTED)
            if (permission != PermissionState.GRANTED) {
                applyResult(session, engine.handle(session.state, RecordingEvent.PermissionChanged(permission)))
            } else {
                applyResult(session, engine.handle(session.state, RecordingEvent.BatteryChanged(percent)))
            }
        }
    }

    private fun onTimeChanged(session: RuntimeSession) {
        if (sessions[session.state.tripId] !== session) return
        val now = dependencies.now()
        val event = if (session.state.tripEndAt?.let { now >= it } == true) {
            RecordingEvent.TripEnded(now)
        } else {
            RecordingEvent.TimeChanged(now)
        }
        applyResult(session, engine.handle(session.state, event))
        if (sessions[session.state.tripId] === session) scheduleTimeCheck(session)
    }

    private fun applyResult(session: RuntimeSession, result: RecordingResult) {
        session.state = result.state
        result.effects.forEach { effect ->
            when (effect) {
                is RecordingEffect.PublishCollectionState -> {
                    dependencies.scheduler.enqueueCollectionState(session.state.accountId, session.state.tripId)
                    dependencies.observer.onPhase(session.state.tripId, "publish:${effect.state.name}")
                }
                is RecordingEffect.NotifyActionRequired -> notifications.actionRequired(effect.reason)
                RecordingEffect.NotifyTripEnded -> notifications.tripEnded()
                RecordingEffect.StopCollector -> dependencies.locations.remove(session.listener)
                is RecordingEffect.EnqueueVisit -> Unit
            }
        }
        when (session.state.collectorState) {
            CollectorState.DORMANT -> requestDormant(session)
            CollectorState.BLOCKED, CollectorState.ENDED -> removeSession(session)
            else -> Unit
        }
    }

    private fun failClosed(session: RuntimeSession, reason: FailureReason) {
        if (session.state.collectorState !in setOf(CollectorState.BLOCKED, CollectorState.ENDED)) {
            applyResult(session, engine.handle(session.state, RecordingEvent.OsBlocked))
        }
        notifications.actionRequired(reason)
    }

    private fun persistMissingBlocked(userId: String, tripId: String) {
        val database = StogDatabase.get(this)
        if (database.accountOwnershipDao().find(userId) == null) return
        val blocked = MemberRecordingState(
            userId,
            tripId,
            userId,
            CollectorState.BLOCKED,
            PermissionState.UNKNOWN,
        )
        database.memberCollectionStateDao().save(blocked.toEntity(dependencies.now()))
        dependencies.scheduler.enqueueCollectionState(userId, tripId)
        notifications.actionRequired(FailureReason.OS_BLOCK)
    }

    private fun removeSession(session: RuntimeSession) {
        handler.removeCallbacks(session.timeCheck)
        dependencies.locations.remove(session.listener)
        SetLogRecentLocationCache.remove(
            session.state.accountId,
            session.state.tripId,
            session.state.userId,
        )
        sessions.remove(session.state.tripId, session)
        if (sessions.isEmpty()) stopSelf()
    }

    private fun scheduleTimeCheck(session: RuntimeSession) {
        handler.removeCallbacks(session.timeCheck)
        val now = dependencies.now()
        val target = listOfNotNull(
            if (session.state.collectorState == CollectorState.ACTIVE) {
                (session.state.lastTransitionAt ?: session.state.enteredAt)?.plus(recordingTuning(this).dormantAfterMillis)
            } else null,
            session.state.tripEndAt,
        ).minOrNull() ?: return
        val delay = (target - now).coerceAtLeast(0L)
        handler.postDelayed(session.timeCheck, delay)
        dependencies.observer.onPhase(session.state.tripId, "timer:$delay")
    }

    private fun currentBatteryPercent(): Int? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.batteryPercent()

    override fun onDestroy() {
        sessions.values.toList().forEach(::removeSession)
        handler.removeCallbacksAndMessages(null)
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        dependencies.runner.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun activeTripIdsForHost(): Set<String> = sessions.keys.toSet()

    companion object {
        private const val EXTRA_TRIP_ID = "recording_trip_id"
        private const val EXTRA_USER_ID = "recording_user_id"
        private const val EXTRA_ACCESS_TOKEN = "recording_access_token"
        private const val EXTRA_PLANNED_END_DATE = "recording_planned_end_date"
        internal const val PHASE_FOREGROUND = "foreground"
        internal const val PHASE_LOCATION_REQUESTED = "location_requested"

        fun intent(context: Context, tripId: String, userId: String, accessToken: String, plannedEndDate: String?) =
            Intent(context, TripLocationService::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_ACCESS_TOKEN, accessToken)
                .putExtra(EXTRA_PLANNED_END_DATE, plannedEndDate)
    }
}

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}

internal object RecordingBootRuntime {
    var recover: (Context, () -> Unit) -> Unit = { context, complete ->
        RecordingActivationCoordinator(context).recover(complete)
    }
    var finished: () -> Unit = {}

    fun reset() {
        recover = { context, complete -> RecordingActivationCoordinator(context).recover(complete) }
        finished = {}
    }
}

class RecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            RecordingBootRuntime.recover(context.applicationContext) {
                pendingResult.finish()
                RecordingBootRuntime.finished()
            }
        }
    }
}

internal class RecordingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun collectionActive(): android.app.Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.stog_app_icon)
            .setContentTitle("여행 수집 중")
            .setContentText("참여자별 셀 기록을 안전하게 보관하고 있어요.")
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .build()
    }

    fun tripEnded() {
        ensureChannel()
        manager.notify(
            ACTION_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.stog_app_icon)
                .setContentTitle("여행이 종료되었어요")
                .setContentText("보관함에서 셀과 동선 기록을 확인해보세요.")
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build(),
        )
    }

    fun actionRequired(reason: FailureReason) {
        ensureChannel()
        val text = when (reason) {
            FailureReason.PERMISSION -> "위치 권한을 다시 허용해야 여행 수집을 이어갈 수 있어요."
            FailureReason.BATTERY -> "배터리를 충전한 뒤 앱을 열어 여행 수집을 이어가세요."
            FailureReason.OS_BLOCK -> "Android가 여행 수집을 막았습니다. 앱에서 설정을 확인하세요."
        }
        manager.notify(
            ACTION_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.stog_app_icon)
                .setContentTitle("여행 수집을 확인해주세요")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build(),
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "여행 수집", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val COLLECTION_NOTIFICATION_ID = 8101
        private const val ACTION_NOTIFICATION_ID = 8102
        private const val CHANNEL_ID = "trip_collection"
    }
}
