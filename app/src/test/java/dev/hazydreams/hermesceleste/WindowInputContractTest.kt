package dev.hazydreams.hermesceleste

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowInputContractTest {
    @Test
    fun mainActivityResizesTheConversationViewportForTheKeyboard() {
        val manifest = manifest().readText()
        val mainActivity = Regex(
            """<activity\s+[^>]*android:name="\.MainActivity"[^>]*>""",
        ).find(manifest)?.value.orEmpty()

        assertTrue(
            "MainActivity should resize its available conversation viewport when the keyboard opens",
            mainActivity.contains("android:windowSoftInputMode=\"adjustResize\""),
        )
    }

    private fun manifest(): File {
        val candidates = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Could not locate AndroidManifest.xml")
    }
}
