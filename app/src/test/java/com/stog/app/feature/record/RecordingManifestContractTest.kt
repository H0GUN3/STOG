package com.stog.app.feature.record

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class RecordingManifestContractTest {
    @Test
    fun manifestDeclaresBackgroundLocationForegroundServiceAndBootRecovery() {
        val manifest = xml(source("AndroidManifest.xml"))
        val permissions = elements(manifest, "uses-permission")
            .map { it.getAttribute("android:name") }
            .toSet()
        assertTrue("android.permission.ACCESS_BACKGROUND_LOCATION" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_LOCATION" in permissions)
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)

        val service = elements(manifest, "service").single {
            it.getAttribute("android:name") == ".feature.record.TripLocationService"
        }
        assertEquals("false", service.getAttribute("android:exported"))
        assertEquals("location", service.getAttribute("android:foregroundServiceType"))
        val receiver = elements(manifest, "receiver").single {
            it.getAttribute("android:name") == ".feature.record.RecordingBootReceiver"
        }
        assertEquals("false", receiver.getAttribute("android:exported"))
    }

    @Test
    fun recordingContractNumbersAreResourceBackedAndManifestHasNoCredentialValue() {
        val tuning = xml(source("res/values/recording_tuning.xml"))
        val values = elements(tuning, "integer").associate { it.getAttribute("name") to it.textContent.trim() }
        assertEquals("50", values["recording_location_distance_filter_m"])
        assertEquals("2", values["recording_cell_transition_confirm"])
        assertEquals("60", values["recording_dormant_after_minutes"])
        assertEquals("30", values["recording_visit_dwell_minutes"])
        assertEquals("15", values["recording_battery_cutoff_percent"])

        val manifestText = source("AndroidManifest.xml").readText()
        assertFalse(manifestText.contains("Bearer "))
        assertFalse(manifestText.contains("access_token"))
        assertFalse(manifestText.contains("client_secret"))
    }

    private fun source(relativeFile: String): File = sequenceOf(
        File("src/main/$relativeFile"),
        File("app/src/main/$relativeFile"),
    ).firstOrNull(File::isFile) ?: error("Could not find $relativeFile")

    private fun xml(file: File): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)
        .documentElement

    private fun elements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return List(nodes.length) { nodes.item(it) as Element }
    }
}
