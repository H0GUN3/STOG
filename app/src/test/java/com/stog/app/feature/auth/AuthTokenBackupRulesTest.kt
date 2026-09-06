package com.stog.app.feature.auth

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AuthTokenBackupRulesTest {
    @Test
    fun excludesAuthPreferencesFromEveryAndroidBackupChannel() {
        assertExcludesAuthPreferences(rootElement("backup_rules.xml"))

        val dataExtractionRules = rootElement("data_extraction_rules.xml")
        assertExcludesAuthPreferences(child(dataExtractionRules, "cloud-backup"))
        assertExcludesAuthPreferences(child(dataExtractionRules, "device-transfer"))
    }

    @Test
    fun keepsAndroidBackupEnabledForNonAuthData() {
        val application = child(rootElement(mainSourceFile("AndroidManifest.xml")), "application")

        assertEquals(
            "true",
            application.getAttribute("android:allowBackup"),
        )
    }

    private fun rootElement(fileName: String): Element = rootElement(resourceFile(fileName))

    private fun rootElement(file: File): Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)
        .documentElement

    private fun resourceFile(fileName: String): File = mainSourceFile("res/xml/$fileName")

    private fun mainSourceFile(path: String): File = sequenceOf(
        File("src/main/$path"),
        File("app/src/main/$path"),
    ).firstOrNull(File::isFile)
        ?: error("Could not find $path")

    private fun child(parent: Element, tagName: String): Element = (0 until parent.childNodes.length)
        .asSequence()
        .map(parent.childNodes::item)
        .filterIsInstance<Element>()
        .first { it.tagName == tagName }

    private fun assertExcludesAuthPreferences(parent: Element) {
        val excludes = (0 until parent.childNodes.length)
            .asSequence()
            .map(parent.childNodes::item)
            .filterIsInstance<Element>()
            .filter { it.tagName == "exclude" }
            .any {
                it.getAttribute("domain") == "sharedpref" &&
                    it.getAttribute("path") == "stog_auth.xml"
            }

        assertTrue("Auth preferences must be excluded from backup", excludes)
    }
}
