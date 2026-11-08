package net.weero.measix.pilot.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import net.weero.measix.pilot.ui.adaptive.AdaptiveLayoutDefaults
import net.weero.measix.pilot.ui.theme.asChatChrome
import kotlin.uuid.Uuid

internal data class OverviewMessage(
    val nodeId: Uuid,
    val user: Boolean,
    val snippet: String,
    val attachmentCount: Int,
    val favorite: Boolean,
)

internal data class OverviewCard(val id: Uuid, val messages: List<OverviewMessage>)

/** Groups the current display branch without changing its individual navigation targets. */
internal fun projectChatOverview(
    nodes: List<MessageNode>,
    favorites: Set<Uuid>,
    query: String,
): List<OverviewCard> {
    val groups = linkedMapOf<Uuid, MutableList<OverviewMessage>>()
    var groupId: Uuid? = null
    val term = query.trim()
    for (node in nodes) {
        val message = node.currentMessage
        val user = message.role == MessageRole.USER
        if (groupId == null || user) groupId = node.id
        val text = message.toText().trim()
        val match = if (term.isEmpty()) 0 else text.indexOf(term, ignoreCase = true)
        if (match < 0) continue
        val start = (match - 12).coerceAtLeast(0)
        val end = (match + maxOf(term.length, 160)).coerceAtMost(text.length)
        val snippet = buildString {
            if (start > 0) append("…")
            append(text.substring(start, end).replace('\n', ' ').replace('\r', ' '))
            if (end < text.length) append("…")
        }
        val attachmentCount = message.parts.count { it.isOverviewAttachment() } +
            message.parts.filterIsInstance<UIMessagePart.Tool>().sumOf { tool -> tool.output.count { it.isOverviewAttachment() } }
        groups.getOrPut(groupId) { mutableListOf() } += OverviewMessage(node.id, user, snippet, attachmentCount, node.id in favorites)
    }
    return groups.map { (id, messages) -> OverviewCard(id, messages.toList()) }
}

private fun UIMessagePart.isOverviewAttachment(): Boolean = when (this) {
    is UIMessagePart.Image, is UIMessagePart.Video, is UIMessagePart.Audio, is UIMessagePart.Document -> true
    else -> false
}

@Composable
internal fun ChatOverview(
    innerPadding: PaddingValues,
    snapshot: ConversationPresentationSnapshot,
    favoriteNodeIds: Set<Uuid>,
    currentNodeId: Uuid?,
    hazeState: HazeState,
    onJumpToMessage: (Uuid) -> Unit,
) {
    var query by remember(snapshot.conversationId) { mutableStateOf("") }
    val cards = remember(snapshot.nodes, favoriteNodeIds, query) { projectChatOverview(snapshot.nodes, favoriteNodeIds, query) }
    val listState = rememberLazyListState()
    LaunchedEffect(currentNodeId, query) {
        val index = if (query.isBlank()) cards.indexOfFirst { card ->
            card.messages.any { it.nodeId == currentNodeId }
        } else 0
        if (index in cards.indices) listState.requestScrollToItem(index)
    }

    Column(
        modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
            .widthIn(max = AdaptiveLayoutDefaults.ReadableContentMaxWidth)
            .fillMaxSize()
            .hazeSource(state = hazeState),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = { Icon(HugeIcons.Search01, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.clear_search))
                }
            },
            singleLine = true,
        )
        if (query.isNotBlank()) {
            Text(
                stringResource(R.string.chat_overview_matches, cards.sumOf { it.messages.size }),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + innerPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(cards, key = { it.id.toString() }) { card ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer.asChatChrome(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        card.messages.forEachIndexed { index, message ->
                            if (index > 0) HorizontalDivider()
                            OverviewMessageRow(message, query.trim(), message.nodeId == currentNodeId) { onJumpToMessage(message.nodeId) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewMessageRow(message: OverviewMessage, query: String, current: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val snippet = message.snippet.ifBlank { stringResource(R.string.chat_overview_message_without_text) }
    val styled = remember(snippet, query, colors) {
        buildAnnotatedString {
            append(snippet)
            if (query.isNotEmpty()) {
                var index = snippet.indexOf(query, ignoreCase = true)
                while (index >= 0) {
                    val end = index + query.length
                    addStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer), index, end)
                    index = snippet.indexOf(query, end, ignoreCase = true)
                }
            }
        }
    }
    Surface(
        color = when {
            current -> colors.secondaryContainer.asChatChrome()
            message.user -> colors.primaryContainer.asChatChrome()
            else -> colors.surfaceContainer.asChatChrome()
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                styled,
                maxLines = if (query.isEmpty()) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (current || message.attachmentCount > 0 || message.favorite) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current) Text(stringResource(R.string.chat_overview_reading_position), style = MaterialTheme.typography.labelSmall)
                    if (message.attachmentCount > 0) {
                        Text(stringResource(R.string.chat_queue_attachments, message.attachmentCount), style = MaterialTheme.typography.labelSmall)
                    }
                    if (message.favorite) Text(stringResource(R.string.model_list_favorite), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
