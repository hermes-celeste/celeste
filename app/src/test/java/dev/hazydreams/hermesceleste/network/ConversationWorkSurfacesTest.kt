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
    fun delegateAgentStreamIsBoundedAndDropsOrphanProgress() {
        val orphan = updateDelegateAgentActivity(
            agents = emptyList(),
            eventType = "subagent.progress",
            payload = Json.parseToJsonElement(
                """{"subagent_id":"agent-a","status":"running","text":"orphan"}""",
            ).jsonObject,
        )
        assertEquals(emptyList<DelegateAgentActivity>(), orphan)

        var agents = updateDelegateAgentActivity(
            agents = emptyList(),
            eventType = "subagent.start",
            payload = Json.parseToJsonElement(
                """{"subagent_id":"agent-a","goal":"Inspect events","status":"running"}""",
            ).jsonObject,
        )
        repeat(30) { index ->
            agents = updateDelegateAgentActivity(
                agents = agents,
                eventType = "subagent.progress",
                payload = Json.parseToJsonElement(
                    """{"subagent_id":"agent-a","status":"running","text":"line $index"}""",
                ).jsonObject,
            )
        }

        assertEquals(24, agents.single().stream.size)
        assertEquals("line 6", agents.single().stream.first().text)
        assertEquals("line 29", agents.single().stream.last().text)
    }

    @Test
    fun delegateAgentRecordsAndRetainedTextAreBoundedAndRequireStableIds() {
        val missingIdentity = updateDelegateAgentActivity(
            agents = emptyList(),
            eventType = "subagent.start",
            payload = Json.parseToJsonElement(
                """{"parent_id":"parent","task_index":0,"goal":"No stable identity"}""",
            ).jsonObject,
        )
        assertEquals(emptyList<DelegateAgentActivity>(), missingIdentity)

        var agents = emptyList<DelegateAgentActivity>()
        repeat(30) { index ->
            agents = updateDelegateAgentActivity(
                agents = agents,
                eventType = "subagent.start",
                payload = Json.parseToJsonElement(
                    """{"subagent_id":"agent-$index","goal":"${"g".repeat(260)}","model":"${"m".repeat(120)}"}""",
                ).jsonObject,
            )
        }

        assertEquals(24, agents.size)
        assertEquals("agent-6", agents.first().id)
        assertEquals("agent-29", agents.last().id)
        assertEquals(220, agents.last().goal.length)
        assertEquals(96, agents.last().model?.length)
    }

    @Test
    fun interruptSettlesOnlyActiveDelegateAgents() {
        var agents = updateDelegateAgentActivity(
            agents = emptyList(),
            eventType = "subagent.start",
            payload = Json.parseToJsonElement(
                """{"subagent_id":"running","goal":"Still working"}""",
            ).jsonObject,
        )
        agents = updateDelegateAgentActivity(
            agents = agents,
            eventType = "subagent.start",
            payload = Json.parseToJsonElement(
                """{"subagent_id":"finished","goal":"Already done"}""",
            ).jsonObject,
        )
        agents = updateDelegateAgentActivity(
            agents = agents,
            eventType = "subagent.complete",
            payload = Json.parseToJsonElement(
                """{"subagent_id":"finished","status":"completed","summary":"Done"}""",
            ).jsonObject,
        )

        val interrupted = interruptActiveDelegateAgents(agents)

        assertEquals(DelegateAgentStatus.Interrupted, interrupted.first().status)
        assertEquals("Interrupted with the parent turn.", interrupted.first().stream.last().text)
        assertEquals(DelegateAgentStatus.Completed, interrupted.last().status)
        assertEquals("Done", interrupted.last().summary)
    }

    @Test
    fun delegateAgentCompletionMapsFailureInterruptionAndInvalidTerminalStatus() {
        fun completed(status: String, extra: String = ""): DelegateAgentActivity {
            var agents = updateDelegateAgentActivity(
                agents = emptyList(),
                eventType = "subagent.start",
                payload = Json.parseToJsonElement(
                    """{"subagent_id":"agent-a","goal":"Inspect events","status":"running"}""",
                ).jsonObject,
            )
            agents = updateDelegateAgentActivity(
                agents = agents,
                eventType = "subagent.complete",
                payload = Json.parseToJsonElement(
                    """{"subagent_id":"agent-a","status":"$status"$extra}""",
                ).jsonObject,
            )
            return agents.single()
        }

        val timeout = completed("timeout", ",\"duration_seconds\":12")
        val interrupted = completed("cancelled", ",\"summary\":\"Stopped by parent\"")
        val invalid = completed("running")

        assertEquals(DelegateAgentStatus.Failed, timeout.status)
        assertEquals("Timed out after 12s", timeout.summary)
        assertEquals(true, timeout.stream.last().isError)
        assertEquals(DelegateAgentStatus.Interrupted, interrupted.status)
        assertEquals("Stopped by parent", interrupted.summary)
        assertEquals(DelegateAgentStatus.Failed, invalid.status)
    }

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
