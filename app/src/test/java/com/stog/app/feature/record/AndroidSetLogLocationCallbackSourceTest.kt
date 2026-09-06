package com.stog.app.feature.record

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidSetLogLocationCallbackSourceTest {
    @Test
    fun modernAndroidCurrentLocationDoesNotRegisterLegacyListener() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED

            override fun checkPermission(permission: String, pid: Int, uid: Int) =
                PackageManager.PERMISSION_GRANTED
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 35.815
                longitude = 127.15
                accuracy = 12.5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            },
        )
        val source = privateAndroidSource(context, manager)
        var update: SetLogSingleUpdate? = null

        val cancellation = source.requestSingleUpdate(
            LocationManager.GPS_PROVIDER,
            8_000L,
        ) { update = it }

        assertTrue(shadow.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(update is SetLogSingleUpdate.Fix)
        cancellation.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    private fun privateAndroidSource(
        context: Context,
        manager: LocationManager,
    ): SetLogLocationCallbackSource {
        val type = Class.forName(
            "com.stog.app.feature.record.AndroidSetLogLocationCallbackSource",
        )
        val constructor = type.declaredConstructors.first {
            it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == LocationManager::class.java &&
                it.parameterTypes[2] == Handler::class.java
        }.apply { isAccessible = true }
        return constructor.newInstance(context, manager, Handler(Looper.getMainLooper())) as SetLogLocationCallbackSource
    }
}
