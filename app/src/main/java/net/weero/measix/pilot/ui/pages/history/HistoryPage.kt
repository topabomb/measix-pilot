package net.weero.measix.pilot.ui.pages.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import net.weero.measix.pilot.utils.plus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.service.AssistantCatalogReadState
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.rememberChatNavigation
import net.weero.measix.pilot.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val chatNavigation = rememberChatNavigation(navController)
    val snackbarHostState = remember { SnackbarHostState() }
    var deletionCandidates by remember { mutableStateOf<List<ConversationSummary>?>(null) }
    val selection by vm.selection.collectAsStateWithLifecycle()
    val operationRunning by vm.running.collectAsStateWithLifecycle()
    val commandFailure by vm.commandFailure.collectAsStateWithLifecycle()
    val deletedNavigation by vm.deletedNavigation.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val pendingDeletion by vm.pendingDeletion.collectAsStateWithLifecycle()
    pendingDeletion?.let { pending ->
        net.weero.measix.pilot.ui.pages.chat.PendingInputDeletionDialog(
            count = pending.preview.pendingInputCount,
            onConfirm = { vm.confirmDeletion(pending) },
            onDismiss = { vm.dismissDeletion(pending) },
            enabled = !operationRunning,
        )
    }
    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)
    LaunchedEffect(deletedNavigation) {
        deletedNavigation.forEach { result -> vm.applyDeletedNavigation(result) {
            navController.replaceDeletedConversation(result.receipt, result.continuation)
        } }
    }
    LaunchedEffect(undo) {
        val token = undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(snackMessageDeleted, snackMessageUndo, withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed) vm.restoreConversation(token)
        else vm.discardRestoreToken(token)
    }

    val conversations = vm.conversations.collectAsStateWithLifecycle().value
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var previousSort by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(sortOrder) }
    LaunchedEffect(sortOrder) {
        if (sortOrder == null) return@LaunchedEffect
        if (previousSort != null && previousSort != sortOrder) listState.scrollToItem(0)
        previousSort = sortOrder
    }
    val assistantCatalog = vm.assistantCatalog.collectAsStateWithLifecycle().value
    val readFailure = vm.readFailure.collectAsStateWithLifecycle().value

    val selectedConversations = selection?.let { selected ->
        conversations.filter { it.id in selected.ids && it.selection == selected.realm }
    }.orEmpty()
    val listRealm = conversations.firstOrNull()?.selection
    LaunchedEffect(listRealm, selection?.realm) {
        deletionCandidates = deletionCandidates?.takeIf { candidates ->
            candidates.isNotEmpty() && candidates.all { it.selection == listRealm }
        }
    }
    BackHandler(selection != null) {
        if (deletionCandidates != null) deletionCandidates = null else vm.exitSelection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selection != null) stringResource(R.string.history_page_selected_count, selectedConversations.size)
                        else stringResource(R.string.history_page_current_space),
                    )
                },
                navigationIcon = {
                    if (selection != null) {
                        TextButton(onClick = vm::exitSelection) { Text(stringResource(R.string.cancel)) }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (selection != null) {
                        IconButton(
                            onClick = { deletionCandidates = selectedConversations },
                            enabled = !operationRunning && selectedConversations.isNotEmpty(),
                        ) {
                            Icon(HugeIcons.Delete01, stringResource(R.string.history_page_delete))
                        }
                    } else {
                        TextButton(
                            onClick = { conversations.firstOrNull()?.let { vm.beginSelection(it) } },
                            enabled = !operationRunning && conversations.isNotEmpty(),
                        ) { Text(stringResource(R.string.history_page_select)) }
                        IconButton(onClick = { navController.navigate(Screen.MessageSearch) }) {
                            Icon(HugeIcons.GlobalSearch, stringResource(R.string.history_page_search_messages))
                        }
                        IconButton(
                            onClick = { deletionCandidates = conversations },
                            enabled = !operationRunning,
                        ) {
                            Icon(HugeIcons.Delete01, stringResource(R.string.history_page_delete_all))
                        }
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        LazyColumn(
            state = listState,
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            commandFailure?.let { detail -> item("command_failure") {
                if (detail.contains("conversation_pending_inputs_changed") || detail.contains("conversation_pending_inputs_confirmation_required")) {
                    Text(stringResource(R.string.chat_queue_changed), color = MaterialTheme.colorScheme.error)
                }
                net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure(detail)
            } }
            (assistantCatalog as? AssistantCatalogReadState.Unavailable)?.let { unavailable ->
                item("configuration_failure") {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(unavailable.detail, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            readFailure?.let { detail ->
                item("history_failure") {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(detail, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = vm::retry) { Text(stringResource(R.string.application_recovery_retry)) }
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                val modifier = Modifier.fillMaxWidth().animateItem()
                val currentSelection = selection
                if (currentSelection != null) {
                    ConversationItem(
                        conversation = conversation,
                        selected = conversation.id in currentSelection.ids,
                        onClick = { vm.toggleSelection(conversation) },
                        modifier = modifier,
                    )
                } else {
                    SwipeableConversationItem(
                        conversation = conversation,
                        onClick = { chatNavigation.existingChat(conversation.id) },
                        onLongClick = { vm.beginSelection(conversation, select = true) },
                        onDelete = { vm.deleteForUndo(conversation) },
                        onTogglePin = { vm.togglePinStatus(conversation) },
                        modifier = modifier,
                    )
                }
            }
        }
    }

    deletionCandidates?.let { candidates ->
        val bulkSelection = selection != null
        AlertDialog(
            onDismissRequest = { deletionCandidates = null },
            title = {
                Text(stringResource(if (bulkSelection) R.string.history_page_delete else R.string.history_page_delete_all_conversations))
            },
            text = {
                Text(
                    if (bulkSelection) stringResource(R.string.history_page_delete_selected_confirmation, candidates.size)
                    else stringResource(R.string.history_page_delete_all_confirmation),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteConversations(candidates)
                        deletionCandidates = null
                    },
                    enabled = !operationRunning && candidates.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletionCandidates = null }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: ConversationSummary,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.settledValue) {
        when (dismissState.settledValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(25)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onLongClick = onLongClick,
            onClick = onClick
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationSummary,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
) {
    val interaction = if (selected != null) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier.then(interaction),
    ) {
        ListItem(
            supportingContent = {
                Text(conversation.sortTime.toLocalDateTime())
            },
            trailingContent = {
                if (selected != null) {
                    Checkbox(checked = selected, onCheckedChange = null)
                } else IconButton(
                    onClick = onTogglePin
                ) {
                    Icon(
                        if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                        contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                            R.string.history_page_pin
                        )
                    )
                }
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (conversation.isPinned) {
                    Icon(
                        imageVector = HugeIcons.Pin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                        .trim(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
