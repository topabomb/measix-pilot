package net.weero.measix.pilot.ui.pages.chat

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chrisbanes.haze.HazeState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ConversationContextSummary
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.toPresentationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatOverviewWorkloadAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun opensSearchesAndScrollsOneThousandHistoryNodes() {
        val content = "A long overview entry with ordinary text. ".repeat(160)
        val nodes = List(1_000) { index ->
            MessageNode.of(UIMessage.user("Chapter $index $content\nOverview marker $index"))
        }
        val header = overviewHeader()
        val snapshot = ConversationPresentationSnapshot(header.id, header, nodes, stream = null)
        val visible = mutableStateOf(false)
        var selected: Uuid? = null
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    ChatOverview(PaddingValues(), snapshot, emptySet(), nodes.first().id, remember { HazeState() }) {
                        selected = it
                    }
                }
            }
        }
        val list = compose.onNode(history)
        fun listPosition() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val clear = compose.activity.getString(R.string.clear_search)
        val marker = "Overview marker 710"
        repeat(3) { sample ->
            var start = SystemClock.elapsedRealtime()
            compose.runOnIdle { visible.value = true }
            awaitRow("Chapter 0 ")
            val open = SystemClock.elapsedRealtime() - start

            start = SystemClock.elapsedRealtime()
            compose.onNode(search).performTextReplacement(marker)
            awaitRow(marker)
            val find = SystemClock.elapsedRealtime() - start
            compose.runOnIdle { selected = null }
            compose.onNode(row(marker)).performClick()
            compose.runOnIdle { assertEquals(nodes[710].id, selected) }

            compose.onNodeWithContentDescription(clear).performClick()
            awaitRow("Chapter 0 ")
            list.performScrollToIndex(600)
            awaitRow("Chapter 600 ")
            val beforeSwipe = listPosition()
            start = SystemClock.elapsedRealtime()
            list.performTouchInput { swipeUp() }
            compose.waitUntil(5_000) { listPosition() > beforeSwipe }
            val scroll = SystemClock.elapsedRealtime() - start
            Log.i("PilotOverviewWorkload", "sample=$sample openMs=$open findMs=$find scrollMs=$scroll")
            compose.runOnIdle { visible.value = false }
        }
    }

    @Test fun changesQueryDuringStreamingAndDropsMatchesFromThePreviousVariant() {
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Original answer")))
        val alternate = original.copy(id = Uuid.random(), parts = listOf(UIMessagePart.Text("Alternate answer")))
        val tail = MessageNode(messages = listOf(original, alternate))
        val nodes = listOf(MessageNode.of(UIMessage.user("Question")), tail)
        val header = overviewHeader()
        val durable = ConversationAggregateSnapshot(header.id, header, nodes)
        val stream = TurnStreamProjection(1, Uuid.random(), original.id, original)
        val runtime = mutableStateOf(ConversationRuntimeSnapshot(durable, stream))
        compose.setContent {
            MaterialTheme {
                ChatOverview(PaddingValues(), runtime.value.toPresentationSnapshot(ConversationContextSummary()),
                    emptySet(), tail.id, remember { HazeState() }, onJumpToMessage = {})
            }
        }
        compose.onNode(search).performTextReplacement("Question")
        awaitRow("Question")

        val draft = original.copy(parts = listOf(UIMessagePart.Text("Original answer with new streaming words")))
        compose.runOnIdle {
            runtime.value = ConversationRuntimeSnapshot(durable, stream.copy(assistantMessage = draft))
        }
        compose.onNode(search).performTextReplacement("new streaming words")
        awaitRow("new streaming words")
        compose.onNode(row("Question")).assertDoesNotExist()

        compose.runOnIdle {
            val selectedVariant = tail.copy(messages = listOf(draft, alternate), selectIndex = 1)
            val updated = durable.copy(nodes = listOf(nodes.first(), selectedVariant))
            runtime.value = ConversationRuntimeSnapshot(updated, stream = null)
        }
        val empty = compose.activity.getString(R.string.chat_overview_matches, 0)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(empty).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row("new streaming words")).assertDoesNotExist()
        compose.onNode(search).performTextReplacement("Alternate answer")
        awaitRow("Alternate answer")
    }

    private val history = hasScrollToIndexAction()
    private val search = hasSetTextAction()

    private fun row(text: String) = hasText(text, substring = true) and hasClickAction() and hasAnyAncestor(history)

    private fun awaitRow(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(row(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row(text)).assertIsDisplayed()
    }

    private fun overviewHeader() = ConversationHeader(
        id = Uuid.random(), title = "Overview workload", assistantId = ConfigurationReference.random(),
        folderId = null, isPinned = false, chatSuggestions = emptyList(), customSystemPrompt = null,
        modeInjectionIds = emptySet(), workspaceCwd = null, parentConversationId = null,
        newConversation = false, createAt = 0, updateAt = 0,
    )
}
