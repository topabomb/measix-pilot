package net.weero.measix.pilot.architecture

import me.rerere.common.configuration.ConfigurationReference

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Verifies real Compose consumer isolation, not only projection object identity. */
@RunWith(AndroidJUnit4::class)
class ConversationSnapshotRecompositionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun headerAndStreamingUpdatesOnlyRecomposeTheirActualConsumers() {
        val conversationId = Uuid.random()
        val assistantMessageId = Uuid.random()
        val nodes = buildList {
            repeat(2) { index -> add(UIMessage.user("history-$index").toMessageNode()) }
            add(UIMessage.assistant("initial").copy(id = assistantMessageId).toMessageNode())
        }
        var snapshot by mutableStateOf(
            ConversationRuntimeSnapshot(
                durable = ConversationAggregateSnapshot(
                    conversationId = conversationId,
                    header = header(conversationId),
                    nodes = nodes,
                ),
                stream = TurnStreamProjection(
                    epoch = 1,
                    turnId = Uuid.random(),
                    assistantMessageId = assistantMessageId,
                    assistantMessage = UIMessage.assistant("initial").copy(id = assistantMessageId),
                ),
            )
        )
        val compositions = mutableMapOf<String, Int>()
        val switchedAssistantId = ConfigurationReference.random()
        val movedFolderId = Uuid.random()

        compose.setContent {
            MeasuredHeader(snapshot.durable.header, compositions)
            val rendered = snapshot.toPresentationSnapshot().nodes
            MeasuredNode("history", rendered.first(), compositions)
            MeasuredNode("active", rendered.last(), compositions)
        }
        compose.waitForIdle()
        val initial = compositions.toMap()
        assertTrue(initial.values.all { it > 0 })

        compose.runOnUiThread {
            snapshot = snapshot.copy(
                durable = snapshot.durable.copy(
                    header = snapshot.durable.header.copy(
                        title = "renamed",
                        assistantId = switchedAssistantId,
                        folderId = movedFolderId,
                    )
                )
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("renamed|$switchedAssistantId|$movedFolderId").assertExists()
        assertTrue(compositions.getValue("header") > initial.getValue("header"))
        assertEquals(initial.getValue("history"), compositions.getValue("history"))
        assertEquals(initial.getValue("active"), compositions.getValue("active"))
        val afterHeader = compositions.toMap()

        compose.runOnUiThread {
            snapshot = snapshot.copy(
                stream = requireNotNull(snapshot.stream).copy(
                    assistantMessage = UIMessage.assistant("delta").copy(id = assistantMessageId),
                )
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("delta").assertExists()
        assertEquals(afterHeader.getValue("header"), compositions.getValue("header"))
        assertEquals(initial.getValue("history"), compositions.getValue("history"))
        assertTrue(compositions.getValue("active") > afterHeader.getValue("active"))
    }

    @Composable
    private fun MeasuredHeader(
        header: ConversationHeader,
        compositions: MutableMap<String, Int>,
    ) {
        SideEffect { compositions["header"] = compositions.getOrDefault("header", 0) + 1 }
        Text("${header.title}|${header.assistantId}|${header.folderId}")
    }

    @Composable
    private fun MeasuredNode(
        name: String,
        node: MessageNode,
        compositions: MutableMap<String, Int>,
    ) {
        SideEffect { compositions[name] = compositions.getOrDefault(name, 0) + 1 }
        Text(node.currentMessage.toText())
    }

    private fun header(conversationId: Uuid) = ConversationHeader(
        id = conversationId,
        title = "title",
        assistantId = ConfigurationReference.random(),
        folderId = null,
        isPinned = false,
        chatSuggestions = emptyList(),
        customSystemPrompt = null,
        modeInjectionIds = emptySet(),
        workspaceCwd = null,
        parentConversationId = null,
        newConversation = false,
        createAt = 1L,
        updateAt = 1L,
    )
}
