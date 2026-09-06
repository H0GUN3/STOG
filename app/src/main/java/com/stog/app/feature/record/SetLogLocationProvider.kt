package com.stog.app.feature.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.stog.app.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

private const val SET_LOG_LOCATION_LOG_TAG = "STOG.Location"

private fun setLogLocationLog(message: String) {
    if (!BuildConfig.DEBUG) return
    try {
        Log.d(SET_LOG_LOCATION_LOG_TAG, message)
    } catch (_: RuntimeException) {
        // Host JVM tests do not provide Android's Log implementation.
    }
}

internal data class SetLogLocationPermissionState(
    val fineGranted: Boolean,
    val coarseGranted: Boolean,
)

internal data class SetLogLocationObservation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val sourceTimeMillis: Long,
    val sourceElapsedRealtimeNanos: Long?,
)

internal data class SetLogRecentLocation(
    val provider: String,
    val observation: SetLogLocationObservation,
)

internal enum class SetLogLocationRejection {
    PERMISSION_DENIED, REQUEST_DENIED, PERMISSION_REVOKED, PROVIDER_DISABLED, TIMEOUT,
    NULL_FIX, INVALID_FIX, STALE_FIX, INACCURATE_FIX,
}

internal sealed interface SetLogLocationAcquisition {
    data class Accepted(val fix: SetLogLocationFix) : SetLogLocationAcquisition
    data class Rejected(val reason: SetLogLocationRejection) : SetLogLocationAcquisition
}

internal fun interface SetLogLocationCancellation { fun cancel() }

internal sealed interface SetLogSingleUpdate {
    data class Fix(val observation: SetLogLocationObservation?) : SetLogSingleUpdate
    data object Timeout : SetLogSingleUpdate
    data object ProviderDisabled : SetLogSingleUpdate
}

internal interface SetLogLocationCallbackSource {
    fun isProviderEnabled(provider: String): Boolean
    fun lastKnownLocation(provider: String): SetLogLocationObservation?
    fun requestSingleUpdate(
        provider: String,
        timeoutMillis: Long,
        callback: (SetLogSingleUpdate) -> Unit,
    ): SetLogLocationCancellation
}

internal data class SetLogLocationTuning(
    val maxAgeMillis: Long,
    val maxAccuracyMeters: Double,
    val timeoutMillis: Long,
) {
    init {
        require(maxAgeMillis >= 0L)
        require(maxAccuracyMeters >= 0.0)
        require(timeoutMillis > 0L)
    }
}

internal class SetLogLocationProvider(
    private val permissions: () -> SetLogLocationPermissionState,
    private val source: SetLogLocationCallbackSource,
    private val tuning: SetLogLocationTuning,
    private val wallClockMillis: () -> Long,
    private val monotonicClockNanos: () -> Long,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    constructor(context: Context) : this(context, context.setLogLocationTuning())

    constructor(context: Context, tuning: SetLogLocationTuning) : this(
        permissions = {
            SetLogLocationPermissionState(
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        },
        source = AndroidSetLogLocationCallbackSource(
            context,
            context.getSystemService(LocationManager::class.java),
        ),
        tuning = tuning,
        wallClockMillis = System::currentTimeMillis,
        monotonicClockNanos = SystemClock::elapsedRealtimeNanos,
    )

    fun acquire(callback: (SetLogLocationAcquisition) -> Unit): SetLogLocationCancellation =
        acquire(recent = null, callback)

    fun acquire(
        recent: SetLogRecentLocation?,
        callback: (SetLogLocationAcquisition) -> Unit,
    ): SetLogLocationCancellation {
        val providers = setLogLocationProviders(permissions(), sdkInt)
        if (providers.isEmpty()) {
            callback(SetLogLocationAcquisition.Rejected(SetLogLocationRejection.PERMISSION_DENIED))
            return SetLogLocationCancellation {}
        }
        val enabled = try {
            providers.filter(source::isProviderEnabled)
        } catch (_: SecurityException) {
            callback(SetLogLocationAcquisition.Rejected(SetLogLocationRejection.REQUEST_DENIED))
            return SetLogLocationCancellation {}
        }
        if (enabled.isEmpty()) {
            callback(SetLogLocationAcquisition.Rejected(SetLogLocationRejection.PROVIDER_DISABLED))
            return SetLogLocationCancellation {}
        }
        setLogLocationLog(
            "acquire candidates=${providers.joinToString()} enabled=${enabled.joinToString()}",
        )
        if (recent != null && recent.provider in enabled) {
            val recentResult = evaluate(
                recent.observation,
                SetLogLocationFixProvenance.RECENT_RECORDING,
            )
            if (recentResult is SetLogLocationAcquisition.Accepted) {
                setLogLocationLog("using accepted recording fix provider=${recent.provider}")
                callback(revalidatePermission(recentResult, recent.provider))
                return SetLogLocationCancellation {}
            }
        }
        val lastFix = try {
            enabled.mapNotNull { provider -> source.lastKnownLocation(provider)?.let { Triple(provider, it, evaluate(it, SetLogLocationFixProvenance.LAST_KNOWN)) } }
                .filter { it.third is SetLogLocationAcquisition.Accepted }
                .let { fixes ->
                    fixes.firstOrNull { it.first == LocationManager.FUSED_PROVIDER }
                        ?: fixes
                            .takeIf { LocationManager.FUSED_PROVIDER !in enabled }
                            ?.maxByOrNull { it.second.sourceTimeMillis }
                }
        } catch (_: SecurityException) {
            callback(SetLogLocationAcquisition.Rejected(SetLogLocationRejection.REQUEST_DENIED))
            return SetLogLocationCancellation {}
        }
        if (lastFix != null) {
            setLogLocationLog("using accepted last-known provider=${lastFix.first}")
            callback(revalidatePermission(lastFix.third, lastFix.first))
            return SetLogLocationCancellation {}
        }
        val completed = AtomicBoolean(false)
        val remaining = AtomicInteger(enabled.size)
        val requests = ConcurrentHashMap<String, SetLogLocationCancellation>()
        val lastFailure = AtomicReference<SetLogLocationAcquisition>(
            SetLogLocationAcquisition.Rejected(
            SetLogLocationRejection.TIMEOUT,
            ),
        )
        val fusedPending = AtomicBoolean(LocationManager.FUSED_PROVIDER in enabled)
        val pendingFallback = AtomicReference<Pair<SetLogLocationAcquisition, String>?>(null)

        fun cancelRequests() {
            requests.values.toList().forEach(SetLogLocationCancellation::cancel)
            requests.clear()
        }

        fun finish(result: SetLogLocationAcquisition, provider: String) {
            if (completed.compareAndSet(false, true)) {
                cancelRequests()
                callback(revalidatePermission(result, provider))
            }
        }

        fun finishPendingFallback() {
            if (!fusedPending.get()) {
                pendingFallback.getAndSet(null)?.let { (result, provider) ->
                    finish(result, provider)
                }
            }
        }

        fun reject(result: SetLogLocationAcquisition, provider: String) {
            lastFailure.set(result)
            if (remaining.decrementAndGet() == 0) {
                pendingFallback.getAndSet(null)?.let { (fallback, fallbackProvider) ->
                    finish(fallback, fallbackProvider)
                } ?: finish(lastFailure.get(), provider)
            }
        }

        enabled.forEach { provider ->
            if (completed.get()) return@forEach
            setLogLocationLog("request provider=$provider timeout=${tuning.timeoutMillis}")
            val cancellation = try {
                source.requestSingleUpdate(provider, tuning.timeoutMillis) { update ->
                    if (!completed.get()) {
                        setLogLocationLog(
                            "callback provider=$provider update=${update::class.simpleName}",
                        )
                        val result = when (update) {
                            is SetLogSingleUpdate.Fix -> update.observation?.let {
                                evaluate(it, SetLogLocationFixProvenance.CURRENT_UPDATE)
                            } ?: SetLogLocationAcquisition.Rejected(SetLogLocationRejection.NULL_FIX)
                            SetLogSingleUpdate.Timeout ->
                                SetLogLocationAcquisition.Rejected(SetLogLocationRejection.TIMEOUT)
                            SetLogSingleUpdate.ProviderDisabled ->
                                SetLogLocationAcquisition.Rejected(SetLogLocationRejection.PROVIDER_DISABLED)
                        }
                        when (result) {
                            is SetLogLocationAcquisition.Accepted -> {
                                setLogLocationLog("accepted provider=$provider")
                                if (provider == LocationManager.FUSED_PROVIDER) {
                                    fusedPending.set(false)
                                    finish(result, provider)
                                } else if (fusedPending.get()) {
                                    pendingFallback.compareAndSet(null, result to provider)
                                } else {
                                    finish(result, provider)
                                }
                            }
                            is SetLogLocationAcquisition.Rejected -> {
                                setLogLocationLog("rejected provider=$provider reason=${result.reason}")
                                if (provider == LocationManager.FUSED_PROVIDER) {
                                    fusedPending.set(false)
                                }
                                reject(result, provider)
                                finishPendingFallback()
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {
                setLogLocationLog("request denied provider=$provider")
                if (provider == LocationManager.FUSED_PROVIDER) {
                    fusedPending.set(false)
                }
                reject(SetLogLocationAcquisition.Rejected(SetLogLocationRejection.REQUEST_DENIED), provider)
                finishPendingFallback()
                null
            }
            if (cancellation != null) {
                requests[provider] = cancellation
                if (completed.get()) requests.remove(provider)?.cancel()
            }
        }

        return SetLogLocationCancellation {
            if (completed.compareAndSet(false, true)) cancelRequests()
        }
    }

    private fun revalidatePermission(
        result: SetLogLocationAcquisition,
        provider: String,
    ): SetLogLocationAcquisition {
        if (result !is SetLogLocationAcquisition.Accepted) return result
        val current = permissions()
        val stillGranted = if (provider == LocationManager.GPS_PROVIDER) current.fineGranted else
            current.fineGranted || current.coarseGranted
        if (!stillGranted) {
            return SetLogLocationAcquisition.Rejected(SetLogLocationRejection.PERMISSION_REVOKED)
        }
        return try {
            if (source.isProviderEnabled(provider)) result else
                SetLogLocationAcquisition.Rejected(SetLogLocationRejection.PROVIDER_DISABLED)
        } catch (_: SecurityException) {
            SetLogLocationAcquisition.Rejected(SetLogLocationRejection.REQUEST_DENIED)
        }
    }

    private fun evaluate(
        observation: SetLogLocationObservation,
        provenance: SetLogLocationFixProvenance,
    ): SetLogLocationAcquisition {
        if (
            !observation.latitude.isFinite() || observation.latitude !in -90.0..90.0 ||
            !observation.longitude.isFinite() || observation.longitude !in -180.0..180.0 ||
            observation.sourceTimeMillis <= 0L ||
            !observation.accuracyMeters.isFinite() || observation.accuracyMeters < 0.0
        ) return SetLogLocationAcquisition.Rejected(SetLogLocationRejection.INVALID_FIX)
        if (observation.accuracyMeters > tuning.maxAccuracyMeters) {
            return SetLogLocationAcquisition.Rejected(SetLogLocationRejection.INACCURATE_FIX)
        }
        val sourceNanos = observation.sourceElapsedRealtimeNanos
        val nowNanos = monotonicClockNanos()
        val ageMillis = if (sourceNanos != null && sourceNanos > 0L && nowNanos >= sourceNanos) {
            (nowNanos - sourceNanos) / 1_000_000L
        } else {
            (wallClockMillis() - observation.sourceTimeMillis).coerceAtLeast(0L)
        }
        if (ageMillis > tuning.maxAgeMillis) {
            return SetLogLocationAcquisition.Rejected(SetLogLocationRejection.STALE_FIX)
        }
        return SetLogLocationAcquisition.Accepted(
            SetLogLocationFix(
                observation.latitude,
                observation.longitude,
                observation.accuracyMeters,
                observation.sourceTimeMillis,
                provenance,
            ),
        )
    }
}

private fun Context.integerResource(name: String): Int = resources.getInteger(
    resources.getIdentifier(name, "integer", packageName),
)

private fun Context.setLogLocationTuning() = SetLogLocationTuning(
    integerResource("set_log_location_max_age_seconds") * 1_000L,
    integerResource("set_log_location_max_accuracy_m").toDouble(),
    integerResource("set_log_location_timeout_seconds") * 1_000L,
)

internal fun Context.homeRecommendationLocationTuning() = SetLogLocationTuning(
    integerResource("home_location_max_age_seconds") * 1_000L,
    integerResource("home_location_max_accuracy_m").toDouble(),
    integerResource("home_location_timeout_seconds") * 1_000L,
)

internal fun setLogLocationProviders(
    permissions: SetLogLocationPermissionState,
    sdkInt: Int = Build.VERSION.SDK_INT,
): List<String> = when {
    permissions.fineGranted -> buildList {
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }
    permissions.coarseGranted -> buildList {
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        add(LocationManager.NETWORK_PROVIDER)
    }
    else -> emptyList()
}

private class AndroidSetLogLocationCallbackSource(
    private val context: Context,
    private val manager: LocationManager,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : SetLogLocationCallbackSource {
    override fun isProviderEnabled(provider: String) = manager.isProviderEnabled(provider)

    override fun lastKnownLocation(provider: String): SetLogLocationObservation? {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) throw SecurityException("Location permission revoked")
        return manager.getLastKnownLocation(provider)?.toObservation()
    }

    override fun requestSingleUpdate(
        provider: String,
        timeoutMillis: Long,
        callback: (SetLogSingleUpdate) -> Unit,
    ): SetLogLocationCancellation {
        if (!context.hasAnySetLogLocationPermission()) throw SecurityException("Location permission revoked")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return requestCurrentLocation(provider, timeoutMillis, callback)
        }
        val completed = AtomicBoolean(false)
        lateinit var listener: LocationListener
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                manager.removeSetLogUpdates(listener)
                callback(SetLogSingleUpdate.Timeout)
            }
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    manager.removeSetLogUpdates(this)
                    callback(SetLogSingleUpdate.Fix(location.toObservation()))
                }
            }

            override fun onProviderDisabled(disabledProvider: String) {
                if (disabledProvider == provider && completed.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    manager.removeSetLogUpdates(this)
                    callback(SetLogSingleUpdate.ProviderDisabled)
                }
            }

        }
        handler.postDelayed(timeout, timeoutMillis)
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (error: SecurityException) {
            handler.removeCallbacks(timeout)
            completed.set(true)
            throw error
        }
        return SetLogLocationCancellation {
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                manager.removeSetLogUpdates(listener)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestCurrentLocation(
        provider: String,
        timeoutMillis: Long,
        callback: (SetLogSingleUpdate) -> Unit,
    ): SetLogLocationCancellation {
        val completed = AtomicBoolean(false)
        val cancellation = CancellationSignal()
        val timeout = Runnable {
            if (completed.compareAndSet(false, true)) {
                setLogLocationLog("timeout provider=$provider")
                cancellation.cancel()
                callback(SetLogSingleUpdate.Timeout)
            }
        }
        handler.postDelayed(timeout, timeoutMillis)
        try {
            setLogLocationLog("platform current-location provider=$provider")
            manager.getCurrentLocation(
                provider,
                cancellation,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (completed.compareAndSet(false, true)) {
                    setLogLocationLog("platform callback provider=$provider hasLocation=${location != null}")
                    handler.removeCallbacks(timeout)
                    callback(SetLogSingleUpdate.Fix(location?.toObservation()))
                }
            }
        } catch (error: SecurityException) {
            handler.removeCallbacks(timeout)
            completed.set(true)
            cancellation.cancel()
            throw error
        }
        return SetLogLocationCancellation {
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                cancellation.cancel()
            }
        }
    }
}

private fun LocationManager.removeSetLogUpdates(listener: LocationListener) {
    try {
        removeUpdates(listener)
    } catch (_: SecurityException) {
        // Permission revocation must not prevent timeout/cancellation from completing.
    }
}

private fun Context.hasAnySetLogLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Location.toObservation() = SetLogLocationObservation(
    latitude,
    longitude,
    accuracy.toDouble(),
    time,
    elapsedRealtimeNanos.takeIf { it > 0L },
)
