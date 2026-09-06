package com.stog.app.feature.record

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecordingRuntimeHostTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }

    @Test
    fun grantedPermissionBecomesRevokedWhenBackgroundGrantDisappears() {
        val application = context as Application
        shadowOf(application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        assertEquals(PermissionState.GRANTED, collectionPermissionState(context, previouslyGranted = false))

        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(PermissionState.REVOKED, collectionPermissionState(context, previouslyGranted = true))
    }

    @Test
    fun coarseForegroundPermissionIsGrantedWhenBackgroundPermissionIsGranted() {
        val application = context as Application
        shadowOf(application).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        assertEquals(
            PermissionState.GRANTED,
            collectionPermissionState(context, previouslyGranted = false),
        )
    }

    @Test
    fun osBlockNotificationHasActionableAppDestination() {
        RecordingNotifications(context).actionRequired(FailureReason.OS_BLOCK)

        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.single()
        assertNotNull(posted.contentIntent)
        assertEquals("여행 수집을 확인해주세요", posted.extras.getString("android.title"))
    }
}
