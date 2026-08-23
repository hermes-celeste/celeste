package dev.hazydreams.hermesceleste

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationInputContractTest {
    @Test
    fun openingTheConversationDrawerDismissesComposerInputFromTapAndGesturePaths() {
        val routes = celesteRoutes().readText()

        assertTrue(
            "The menu action should dismiss composer focus before opening the drawer",
            Regex(
                """onOpenDrawer\s*=\s*\{\s*dismissConversationInput\(\)\s*drawerScope\.launch\s*\{\s*drawerState\.open\(\)""",
            ).containsMatchIn(routes),
        )
        assertTrue(
            "Gesture-driven drawer opening should dismiss composer input when the drawer targets Open",
            routes.contains("snapshotFlow { drawerState.targetValue }") &&
                routes.contains("if (targetValue == DrawerValue.Open) dismissConversationInput()"),
        )
    }

    @Test
    fun leavingConversationContentDismissesComposerInput() {
        val routes = celesteRoutes().readText()

        assertTrue(
            "Settings and Gateway navigation should dismiss composer focus",
            routes.contains("if (destination != CelesteDestination.Content) dismissConversationInput()"),
        )
    }

    private fun celesteRoutes(): File {
        val candidates = listOf(
            File("app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteRoutes.kt"),
            File("src/main/java/dev/hazydreams/hermesceleste/ui/CelesteRoutes.kt"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Could not locate CelesteRoutes.kt")
    }
}
