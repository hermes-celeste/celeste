package dev.hazydreams.hermesceleste.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationWorkSurfacesTest {
    @Test
    fun multiFileDiffKeepsStatsAndContentWithItsOwningPath() {
        val message = ConversationMessage(
            role = "changes",
            text = "",
            fileEdits = listOf(
                FileEditOperation(
                    toolId = "patch-1",
                    paths = listOf("ui/First.kt", "ui/Second.kt"),
                    diff = """--- a/ui/First.kt
                        |+++ b/ui/First.kt
                        |@@ -1 +1 @@
                        |-old first
                        |+new first
                        |--- a/ui/Second.kt
                        |+++ b/ui/Second.kt
                        |@@ -1,2 +1,3 @@
                        | keep
                        |+new second
                        |+another line
                    """.trimMargin(),
                    state = FileEditState.Completed,
                ),
            ),
        )

        val files = message.changedFiles()

        assertEquals(listOf("ui/First.kt", "ui/Second.kt"), files.map { it.path })
        assertEquals(1, files[0].additions)
        assertEquals(1, files[0].removals)
        assertEquals(2, files[1].additions)
        assertEquals(0, files[1].removals)
        assertEquals(true, files[0].diffs.single().contains("new first"))
        assertEquals(false, files[0].diffs.single().contains("new second"))
    }

    @Test
    fun repeatedOperationsUseTheMostSevereFinalStateForOneChangedFile() {
        val message = ConversationMessage(
            role = "changes",
            text = "",
            fileEdits = listOf(
                FileEditOperation(
                    toolId = "write-1",
                    paths = listOf("App.kt"),
                    summary = "Wrote App.kt",
                    state = FileEditState.Completed,
                ),
                FileEditOperation(
                    toolId = "patch-2",
                    paths = listOf("App.kt"),
                    summary = "Patch rejected",
                    state = FileEditState.Failed,
                ),
            ),
        )

        val changed = message.changedFiles().single()

        assertEquals("App.kt", changed.path)
        assertEquals(FileEditState.Failed, changed.state)
        assertEquals("Patch rejected", changed.summary)
        assertEquals(emptyList<String>(), changed.diffs)
    }

    @Test
    fun todoSnapshotReconcilesDuplicateStableIdsWithoutChangingSourceOrder() {
        val todos = Json.parseToJsonElement(
            """[
                {"id":"build","content":"Build","status":"pending"},
                {"id":"verify","content":"Verify","status":"pending"},
                {"id":"build","content":"Build","status":"completed"}
            ]""".trimIndent(),
        ).jsonArray

        val progress = decodeTaskProgress(todos)!!

        assertEquals(listOf("build", "verify"), progress.items.map { it.id })
        assertEquals(TaskItemStatus.Completed, progress.items[0].status)
        assertEquals(1, progress.completedCount)
        assertEquals(2, progress.totalCount)
    }

    @Test
    fun emptyTodoSnapshotIsAnExplicitClearWhileMissingTodosAreIgnored() {
        val clear = taskProgressSnapshotFromPayload(
            Json.parseToJsonElement("""{"todos":[]}""").jsonObject,
        )

        assertNotNull(clear)
        assertNull(clear?.progress)
        assertNull(taskProgressSnapshotFromPayload(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun changedSourceLinesThatResembleDiffHeadersStillCountTowardStats() {
        val message = ConversationMessage(
            role = "changes",
            text = "",
            fileEdits = listOf(
                FileEditOperation(
                    toolId = "patch-1",
                    paths = listOf("App.kt"),
                    diff = """--- a/App.kt
                        |+++ b/App.kt
                        |@@ -1 +1 @@
                        |--- flag
                        |+++ counter
                    """.trimMargin(),
                    state = FileEditState.Completed,
                ),
            ),
        )

        val changed = message.changedFiles().single()

        assertEquals(1, changed.additions)
        assertEquals(1, changed.removals)
    }

    @Test
    fun toolPathsKeepLegitimatePrefixesWhileDiffHeadersLoseOneSyntheticPrefix() {
        val args = Json.parseToJsonElement("""{"path":"a/b/File.kt"}""").jsonObject
        val diff = """--- a/a/b/File.kt
            |+++ b/a/b/File.kt
            |@@ -1 +1 @@
            |-old
            |+new
        """.trimMargin()

        assertEquals(listOf("a/b/File.kt"), fileEditPaths(name = "write_file", args = args))
        assertEquals(listOf("a/b/File.kt"), fileEditPaths(name = "patch", args = null, diff = diff))
    }

    @Test
    fun renamedDiffUsesDestinationPathOnceAndDeletionFallsBackToSource() {
        val renameArgs = Json.parseToJsonElement("""{"path":"new.kt"}""").jsonObject
        val renameDiff = """--- a/old.kt
            |+++ b/new.kt
            |@@ -1 +1 @@
            |-old
            |+new
        """.trimMargin()

        val renamePaths = fileEditPaths(name = "patch", args = renameArgs, diff = renameDiff)
        val renamed = ConversationMessage(
            role = "changes",
            text = "",
            fileEdits = listOf(
                FileEditOperation(
                    toolId = "patch-rename",
                    paths = renamePaths,
                    diff = renameDiff,
                    state = FileEditState.Completed,
                ),
            ),
        ).changedFiles()

        assertEquals(listOf("new.kt"), renamePaths)
        assertEquals(listOf("new.kt"), renamed.map { it.path })
        assertEquals(1, renamed.single().additions)
        assertEquals(1, renamed.single().removals)

        val deletionDiff = """--- a/old.kt
            |+++ /dev/null
            |@@ -1 +0,0 @@
            |-old
        """.trimMargin()
        assertEquals(listOf("old.kt"), fileEditPaths(name = "patch", args = null, diff = deletionDiff))
    }

    @Test
    fun v4aPatchArgumentsExposeEveryChangedPathInOrder() {
        val args = Json.parseToJsonElement(
            """{"mode":"patch","patch":"*** Begin Patch\n*** Update File: app/A.kt\n*** Add File: app/B.kt\n*** Delete File: app/C.kt\n*** End Patch"}""",
        ).jsonObject

        assertEquals(
            listOf("app/A.kt", "app/B.kt", "app/C.kt"),
            fileEditPaths(name = "patch", args = args),
        )
    }
}
