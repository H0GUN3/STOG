package com.stog.app.feature.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

internal enum class SetLogEntryPermissionStep {
    REQUEST_CAMERA,
    REQUEST_LOCATION,
    OPEN_LOCATION_SETTINGS,
    READY,
}

internal fun setLogEntryPermissionStep(
    cameraPermissionGranted: Boolean,
    locationPermissionGranted: Boolean,
    locationProviderEnabled: Boolean,
): SetLogEntryPermissionStep = when {
    !cameraPermissionGranted -> SetLogEntryPermissionStep.REQUEST_CAMERA
    !locationPermissionGranted -> SetLogEntryPermissionStep.REQUEST_LOCATION
    !locationProviderEnabled -> SetLogEntryPermissionStep.OPEN_LOCATION_SETTINGS
    else -> SetLogEntryPermissionStep.READY
}

internal fun setLogLocationProviderEnabled(
    finePermissionGranted: Boolean,
    coarsePermissionGranted: Boolean,
    gpsProviderEnabled: Boolean,
    networkProviderEnabled: Boolean,
): Boolean = when {
    finePermissionGranted -> gpsProviderEnabled || networkProviderEnabled
    coarsePermissionGranted -> networkProviderEnabled
    else -> false
}

internal fun Context.isSetLogLocationProviderEnabled(): Boolean {
    val manager = getSystemService(LocationManager::class.java) ?: return false
    val fineGranted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return setLogLocationProviderEnabled(
        finePermissionGranted = fineGranted,
        coarsePermissionGranted = coarseGranted,
        gpsProviderEnabled = runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false),
        networkProviderEnabled = runCatching {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false),
    )
}
