package net.weero.measix.pilot.ui.pages.chat

import net.weero.measix.pilot.utils.mirrorInRtl
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ConversationActivity
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.ui.components.ai.ConversationActivityIndicator
import net.weero.measix.pilot.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: ConversationSummary
    ) : ConversationListItem()
}

@Composable
fun ConversationList(
    currentConversationId: Uuid,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationActivities: Map<Uuid, Set<ConversationActivity>>,
    listState: LazyListState,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (ConversationSummary) -> Unit = {},
    onDelete: (ConversationSummary) -> Unit = {},
    onRegenerateTitle: (ConversationSummary) -> Unit = {},
    onPin: (ConversationSummary) -> Unit = {},
    onMoveToAssistant: (ConversationSummary) -> Unit = {},
    onMoveToFolder: (ConversationSummary) -> Unit = {}
) {
    var hasScrolledToCurrent by remember(currentConversationId) { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        var headerHeight by remember(maxWidth, density) { mutableIntStateOf(0) }
        var footerHeight by remember(maxWidth, density) { mutableIntStateOf(0) }
        val lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
        val minimumListHeight = with(density) {
            2 * maxOf(48.dp.roundToPx(), (lineHeight.toPx() + 10.dp.toPx()).toInt())
        }
        val pinHeader = constraints.maxHeight - headerHeight - footerHeight >= minimumListHeight
        var previouslyPinned by remember { mutableStateOf<Boolean?>(null) }
        // Capture the old layout during composition, before moving the header changes measurement.
        val layoutAnchor = remember(pinHeader) {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index in 1..conversations.itemCount }
        }
        LaunchedEffect(pinHeader) {
            val previous = previouslyPinned
            previouslyPinned = pinHeader
            if (previous != null && previous != pinHeader && layoutAnchor != null) {
                listState.scrollToItem(layoutAnchor.index, maxOf(0, -layoutAnchor.offset))
            }
        }
        LaunchedEffect(currentConversationId, conversations.itemSnapshotList, headerHeight, footerHeight) {
            if (hasScrolledToCurrent || (pinHeader && (headerHeight == 0 || footerHeight == 0))) return@LaunchedEffect
            val snapshot = conversations.itemSnapshotList
            val loadedIndex = snapshot.items.indexOfFirst {
                (it as? ConversationListItem.Item)?.conversation?.id == currentConversationId
            }
            if (loadedIndex >= 0) {
                // The drawer header always has one slot, empty when fixed outside. Paging's
                // loaded snapshot excludes leading placeholders; lazy item indices do not.
                val currentIndex = snapshot.placeholdersBefore + loadedIndex + 1
                val isVisible = listState.layoutInfo.visibleItemsInfo.any {
                    it.index == currentIndex && it.offset >= 0 &&
                        it.offset + it.size <= listState.layoutInfo.viewportEndOffset
                }
                if (!isVisible) listState.scrollToItem(currentIndex)
                hasScrolledToCurrent = true
            }
        }
        val measuredHeader: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)
                .onSizeChanged { headerHeight = it.height }) { header() }
        }
        val measuredFooter: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)
                .onSizeChanged { footerHeight = it.height }) { footer() }
        }
        Column(Modifier.fillMaxSize()) {
            if (pinHeader) measuredHeader()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "drawer_header", contentType = "drawer_header") {
                    if (!pinHeader) measuredHeader()
                }
                if (conversations.itemCount == 0) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Text(
                                text = stringResource(id = R.string.chat_page_no_conversations),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                items(
                    count = conversations.itemCount,
                    key = conversations.itemKey { item ->
                        when (item) {
                            is ConversationListItem.DateHeader -> "date_${item.date}"
                            is ConversationListItem.PinnedHeader -> "pinned_header"
                            is ConversationListItem.Item -> item.conversation.id.toString()
                        }
                    }
                ) { index ->
                    when (val item = conversations[index]) {
                        is ConversationListItem.DateHeader -> {
                            DateHeaderItem(
                                label = item.label,
                                modifier = Modifier.animateItem()
                            )
                        }

                        is ConversationListItem.PinnedHeader -> {
                            PinnedHeader(
                                modifier = Modifier.animateItem()
                            )
                        }

                        is ConversationListItem.Item -> {
                            ConversationItem(
                                conversation = item.conversation,
                                selected = item.conversation.id == currentConversationId,
                                activities = conversationActivities[item.conversation.id].orEmpty(),
                                onClick = onClick,
                                onDelete = onDelete,
                                onRegenerateTitle = onRegenerateTitle,
                                onPin = onPin,
                                onMoveToAssistant = onMoveToAssistant,
                                onMoveToFolder = onMoveToFolder,
                                modifier = Modifier.animateItem()
                            )
                        }

                        null -> {
                            // Placeholder for loading state
                        }
                    }
                }
                if (!pinHeader) {
                    item(key = "drawer_footer", contentType = "drawer_footer") { measuredFooter() }
                }
            }
            if (pinHeader) measuredFooter()
        }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier
) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationSummary,
    selected: Boolean,
    activities: Set<ConversationActivity>,
    modifier: Modifier = Modifier,
    onDelete: (ConversationSummary) -> Unit = {},
    onRegenerateTitle: (ConversationSummary) -> Unit = {},
    onPin: (ConversationSummary) -> Unit = {},
    onMoveToAssistant: (ConversationSummary) -> Unit = {},
    onMoveToFolder: (ConversationSummary) -> Unit = {},
    onClick: (ConversationSummary) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color.Transparent
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50f))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { onClick(conversation) },
                onLongClick = {
                    focusManager.clearFocus(force = true)
                    showDropdownMenu = true
                }
            )
            .background(backgroundColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))

            // 置顶图标
            AnimatedVisibility(conversation.isPinned) {
                Icon(
                    imageVector = HugeIcons.Pin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(activities.isNotEmpty()) {
                ConversationActivityIndicator(
                    activities = activities,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                        )
                    },
                    onClick = {
                        onPin(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            null
                        )
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_regenerate_title))
                    },
                    onClick = {
                        onRegenerateTitle(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Refresh01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_assistant))
                    },
                    onClick = {
                        onMoveToAssistant(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Forward02, null, modifier = Modifier.mirrorInRtl())
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_folder))
                    },
                    onClick = {
                        onMoveToFolder(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Folder01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_delete))
                    },
                    onClick = {
                        onDelete(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Delete01, null)
                    }
                )
            }
        }
    }
}
