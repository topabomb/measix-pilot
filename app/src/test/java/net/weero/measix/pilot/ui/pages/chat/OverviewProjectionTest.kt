package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class OverviewProjectionTest {
    @Test fun `answers retain their own navigation ids and only selected variants match`() {
        val opening = node(MessageRole.ASSISTANT, "Opening")
        val first = node(MessageRole.USER, "Question")
        val reply = node(MessageRole.ASSISTANT, "unrelated text ".repeat(8) + "nearby **code**")
        val hidden = node(MessageRole.ASSISTANT, "Current answer").let { current ->
            current.copy(messages = current.messages + UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("code in a hidden variant")),
            ))
        }
        val second = MessageNode.of(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///photo"))))
        val nodes = listOf(opening, first, reply, hidden, second)
        val overview = projectChatOverview(nodes, setOf(reply.id), "")
        assertEquals(listOf(opening.id, first.id, second.id), overview.map { it.id })
        assertEquals(listOf(first.id, reply.id, hidden.id), overview[1].messages.map { it.nodeId })
        assertEquals(1, overview.last().messages.single().attachmentCount)

        val matches = projectChatOverview(nodes, setOf(reply.id), "CODE")
        val hit = matches.single().messages.single()
        assertEquals(reply.id, hit.nodeId)
        assertTrue(hit.favorite)
        assertTrue(hit.snippet.contains("nearby **code**"))
    }

    @Test fun `a dynamic prefix shifts navigation without changing the target identity`() {
        val first = node(MessageRole.USER, "Question")
        val reply = node(MessageRole.ASSISTANT, "Answer")
        val id = Uuid.random()
        val snapshot = ConversationPresentationSnapshot(id, ConversationHeader(
            id = id, title = "", assistantId = ConfigurationReference.User(Uuid.random()), folderId = null,
            isPinned = false, chatSuggestions = emptyList(), customSystemPrompt = null, modeInjectionIds = emptySet(),
            workspaceCwd = null, parentConversationId = null, newConversation = false, createAt = 0L, updateAt = 0L,
        ), listOf(first, reply), stream = null)
        assertEquals(1, chatListIndex(snapshot, null, reply.id))
        assertEquals(2, chatListIndex(snapshot, ChatListPrefix.MODEL, reply.id))
        assertNull(chatListIndex(snapshot.copy(nodes = listOf(first)), ChatListPrefix.PROVIDER, reply.id))
    }

    private fun node(role: MessageRole, text: String) =
        MessageNode.of(UIMessage(role = role, parts = listOf(UIMessagePart.Text(text))))
}
