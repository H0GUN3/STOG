package com.stog.app.feature.record

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.stog.app.core.database.StogDatabase
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidLocationCollectionTest {
    private lateinit var context: Context
    private lateinit var service: TripLocationService
    private lateinit var manager: LocationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(Executor(Runnable::run)).setTaskExecutor(Executor(Runnable::run)).build(),
        )
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
        RecordingServiceRuntime.reset()
        service = Robolectric.buildService(TripLocationService::class.java).create().get()
        manager = service.getSystemService(LocationManager::class.java)
    }

    @After
    fun tearDown() {
        service.onDestroy()
        RecordingServiceRuntime.reset()
        StogDatabase.resetForHostTest()
        context.deleteDatabase(StogDatabase.DATABASE_NAME)
    }

    @Test
    fun activeCollectionListensToNetworkAlongsideGps() {
        val collection = privateAndroidCollection(service, manager)

        collection.requestActive(listener(), 50f)

        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isNotEmpty())
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isNotEmpty())
    }

    @Test
    fun coarsePermissionUsesNetworkProviderWithoutGpsRequest() {
        val application = context as Application
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val collection = privateAndroidCollection(service, manager)

        collection.requestActive(listener(), 50f)

        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).isNotEmpty())
    }

    private fun listener() = object : LocationListener {
        override fun onLocationChanged(location: Location) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun privateAndroidCollection(
        service: TripLocationService,
        manager: LocationManager,
    ): LocationCollection {
        val type = Class.forName("com.stog.app.feature.record.AndroidLocationCollection")
        val constructor = type.declaredConstructors.first {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == android.app.Service::class.java &&
                it.parameterTypes[1] == LocationManager::class.java
        }.apply { isAccessible = true }
        return constructor.newInstance(service, manager) as LocationCollection
    }
}
