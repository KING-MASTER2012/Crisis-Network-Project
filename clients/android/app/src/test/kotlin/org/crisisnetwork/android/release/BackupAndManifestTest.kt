package org.crisisnetwork.android.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The security properties of the manifest and the backup rules (phase A7). They are easy to lose by
 * accident (a copied sample, a library, a tidy-up), and the damage is silent: the identity or the
 * messages end up in a cloud backup or on another phone.
 */
class BackupAndManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val manifest = ResourceFiles.parse("src/main/AndroidManifest.xml")

    private fun application(): Element = manifest.getElementsByTagName("application").item(0) as Element

    @Test
    fun theAppDoesNotAllowBackups() {
        assertEquals("false", application().getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", application().getAttributeNS(android, "dataExtractionRules"))
    }

    @Test
    fun noDomainIsLeftInCloudBackupOrDeviceTransfer() {
        val rules = ResourceFiles.parse("src/main/res/xml/data_extraction_rules.xml")
        val required = setOf("root", "file", "database", "sharedpref", "external")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.getElementsByTagName(section).item(0) as Element
            val excluded = (0 until node.getElementsByTagName("exclude").length).map { (node.getElementsByTagName("exclude").item(it) as Element).getAttribute("domain") }
            assertEquals("$section must exclude every domain", required, excluded.toSet())
            assertEquals("$section must not include anything", 0, node.getElementsByTagName("include").length)
        }
    }

    @Test
    fun theAppNeverTalksToTheInternet() {
        val permissions = (0 until manifest.getElementsByTagName("uses-permission").length).map { (manifest.getElementsByTagName("uses-permission").item(it) as Element).getAttributeNS(android, "name") }
        assertFalse("The mesh is Bluetooth only: no INTERNET permission", "android.permission.INTERNET" in permissions)
        assertEquals("false", application().getAttributeNS(android, "usesCleartextTraffic"))
    }

    @Test
    fun onlyTheMainActivityIsExported() {
        val exported = mutableListOf<String>()
        for (tag in listOf("activity", "service", "receiver", "provider")) {
            val nodes = manifest.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                if (element.getAttributeNS(android, "exported") == "true") exported += element.getAttributeNS(android, "name")
            }
        }
        assertEquals(listOf(".MainActivity"), exported)
    }

    @Test
    fun theReleaseIsNotDebuggable() {
        assertTrue(application().getAttributeNS(android, "debuggable").isEmpty())
    }
}
