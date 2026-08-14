package dev.hazydreams.hermesceleste.connection

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupExclusionTest {
    @Test
    fun savedConnectionDescriptorIsExcludedFromBackupAndDeviceTransfer() {
        val legacyRules = resource("backup_rules.xml").readText()
        val extractionRules = resource("data_extraction_rules.xml").readText()

        assertTrue(legacyRules.contains("domain=\"sharedpref\" path=\"celeste_connection.xml\""))
        assertTrue(extractionRules.contains("domain=\"sharedpref\" path=\"celeste_connection.xml\""))
        assertTrue(extractionRules.contains("<cloud-backup"))
        assertTrue(extractionRules.contains("<device-transfer>"))
    }

    private fun resource(name: String): File {
        val candidates = listOf(
            File("app/src/main/res/xml/$name"),
            File("src/main/res/xml/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Could not locate Android backup rule $name")
    }
}