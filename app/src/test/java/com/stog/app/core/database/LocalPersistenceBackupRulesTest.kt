package com.stog.app.core.database

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalPersistenceBackupRulesTest {
    @Test
    fun excludesRoomDatabaseAndRawShareImportsFromEveryBackupChannel() {
        assertLocalPersistenceExcluded(root("backup_rules.xml"))

        val extraction = root("data_extraction_rules.xml")
        assertLocalPersistenceExcluded(child(extraction, "cloud-backup"))
        assertLocalPersistenceExcluded(child(extraction, "device-transfer"))
    }

    private fun assertLocalPersistenceExcluded(parent: Element) {
        val exclusions = (0 until parent.childNodes.length)
            .asSequence()
            .map(parent.childNodes::item)
            .filterIsInstance<Element>()
            .filter { it.tagName == "exclude" }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()

        assertTrue("Room databases must not enter backup", "database" to "." in exclusions)
        assertTrue("Legacy raw share files must not enter backup", "file" to "share_imports" in exclusions)
    }

    private fun root(name: String): Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(source("res/xml/$name"))
        .documentElement

    private fun source(path: String): File = sequenceOf(File("src/main/$path"), File("app/src/main/$path"))
        .firstOrNull(File::isFile)
        ?: error("Could not find $path")

    private fun child(parent: Element, name: String): Element = (0 until parent.childNodes.length)
        .asSequence()
        .map(parent.childNodes::item)
        .filterIsInstance<Element>()
        .first { it.tagName == name }
}
