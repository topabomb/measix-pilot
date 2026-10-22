package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.ToolInteractionState
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageEditedFilesTest {
    @Test fun `current production write and edit outputs expose files while legacy results remain readable`() = kotlinx.coroutines.test.runTest {
        val service = io.mockk.mockk<net.weero.measix.pilot.service.workspace.WorkspaceApplicationService>()
        val session = io.mockk.mockk<net.weero.measix.pilot.service.workspace.WorkspaceToolSession>()
        val context = io.mockk.mockk<me.rerere.ai.core.ToolExecutionContext>(relaxed = true)
        io.mockk.coEvery { service.executeTool<Any?>(any(), any(), any()) } coAnswers {
            thirdArg<suspend net.weero.measix.pilot.service.workspace.WorkspaceToolSession.() -> Any?>().invoke(session)
        }
        io.mockk.coEvery { session.writeRootfsText(any(), any(), any(), any()) } returns
            me.rerere.workspace.WorkspaceFileEntry("/workspace/write.txt", "write.txt", false, 4, 2)
        io.mockk.coEvery { session.updateRootfsText(any(), any(), any(), any()) } coAnswers {
            arg<(String) -> String>(3).invoke("old")
            me.rerere.workspace.WorkspaceFileEntry("/workspace/edit.txt", "edit.txt", false, 3, 2)
        }
        val tools = net.weero.measix.pilot.data.ai.tools.createWorkspaceTools(
            net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, "workspace", service, emptyMap(), io.mockk.mockk())
        val outputs = listOf(
            "workspace_write_file" to """{"path":"/workspace/write.txt","text":"text"}""",
            "workspace_edit_file" to """{"path":"/workspace/edit.txt","old_text":"old","new_text":"new"}""",
        ).map { (name, input) ->
            write(input, "").copy(toolName = name, output = tools.single { it.name == name }
                .executeWithContext(context, kotlinx.serialization.json.Json.parseToJsonElement(input)))
        }
        assertEquals(listOf("/workspace/write.txt", "/workspace/edit.txt"), editedWorkspaceFilePaths(outputs))
        assertEquals(listOf("/workspace/legacy.txt"), editedWorkspaceFilePaths(listOf(write("{}", writeResult("/workspace/legacy.txt")))))
    }

    @Test
    fun `malformed input with failed result never crashes or advertises requested file`() {
        val parts = listOf(
            write("[]", """{"error":"invalid_arguments"}"""),
            write("""{"path":[]}""", """{"error":"invalid_arguments"}"""),
            write("""{"path":"/workspace/never-written.txt"}""", """{"error":"text is required"}"""),
            write("""{"path":"/workspace/never-written.txt"}""", """{"status":"failed","reason":"invalid_arguments"}"""),
        )
        assertTrue(editedWorkspaceFilePaths(parts).isEmpty())
    }

    @Test
    fun `successful results use returned paths only and deduplicate in completion order`() {
        val parts = listOf(
            write("[]", writeResult("/workspace/first.txt")),
            edit("""{"path":"/workspace/old.txt"}""", editResult("/workspace/second.txt")),
            write("""{"path":"/workspace/requested.txt"}""", writeResult("/workspace/first.txt")),
        )
        assertEquals(listOf("/workspace/first.txt", "/workspace/second.txt"), editedWorkspaceFilePaths(parts))
    }

    @Test
    fun `bad result root path and incomplete success shape do not fall back to input`() {
        val requested = """{"path":"/workspace/requested.txt","text":"contents"}"""
        val outputs = listOf(
            "not json", "[]", "null", "42",
            """{"path":[]}""",
            """{"path":42,"isDirectory":false,"sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/x.txt"}""",
            """{"path":"/workspace/x.txt","isDirectory":"false","sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":"1","updatedAt":2}""",
            """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":-1,"updatedAt":2}""",
            """{"path":"relative.txt","isDirectory":false,"sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/","isDirectory":false,"sizeBytes":1,"updatedAt":2}""",
            """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":1,"updatedAt":[]}""",
        )
        outputs.forEach { output ->
            assertTrue(output, editedWorkspaceFilePaths(listOf(write(requested, output))).isEmpty())
        }
    }

    @Test
    fun `failure flags refusals and pending calls are never successful file mutations`() {
        val resultWithError = """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":1,"updatedAt":2,"error":"failed"}"""
        val resultWithFailure = """{"path":"/workspace/x.txt","isDirectory":false,"sizeBytes":1,"updatedAt":2,"success":false}"""
        val parts = listOf(
            write("{}", resultWithError),
            write("{}", resultWithFailure),
            write("{}", writeResult("/workspace/denied.txt")).copy(interactionState = ToolInteractionState.Denied("No")),
            write("{}", writeResult("/workspace/pending.txt")).copy(interactionState = ToolInteractionState.AwaitingApproval),
            write("{}", """{"error":"User denied the tool call"}"""),
        )
        assertTrue(editedWorkspaceFilePaths(parts).isEmpty())
    }

    @Test
    fun `read tool media directory and zero replacement results do not advertise edits`() {
        val parts = listOf(
            write("{}", writeResult("/workspace/read.png")).copy(toolName = "workspace_read_file"),
            write("{}", """{"path":"/workspace/folder","isDirectory":true,"sizeBytes":0,"updatedAt":2}"""),
            edit("{}", """{"path":"/workspace/x.txt","replacements":0,"sizeBytes":1,"updatedAt":2}"""),
            edit("{}", """{"path":"/workspace/x.txt","replacements":"1","sizeBytes":1,"updatedAt":2}"""),
            write("{}", writeResult("/workspace/image.png")).copy(output = listOf(UIMessagePart.Image("data:image/png;base64,x"))),
        )
        assertTrue(editedWorkspaceFilePaths(parts).isEmpty())
    }

    @Test
    fun `unfinished tool has no produced file and edit metadata is untouched`() {
        val completed = edit("{}", editResult("/workspace/file.txt"))
        val unfinished = completed.copy(output = emptyList())
        val output = completed.output

        assertTrue(editedWorkspaceFilePaths(listOf(unfinished)).isEmpty())
        assertEquals(listOf("/workspace/file.txt"), editedWorkspaceFilePaths(listOf(completed)))
        assertEquals(output, completed.output)
    }

    private fun write(input: String, output: String) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "write", toolName = "workspace_write_file", input = input,
        output = listOf(UIMessagePart.Text(output)),
    )

    private fun edit(input: String, output: String) = write(input, output).copy(toolName = "workspace_edit_file")

    private fun writeResult(path: String) =
        """{"path":"$path","name":"file.txt","isDirectory":false,"sizeBytes":0,"updatedAt":2}"""

    private fun editResult(path: String) =
        """{"path":"$path","replacements":1,"matchStrategy":"line_trimmed","sizeBytes":1,"updatedAt":2}"""
}
