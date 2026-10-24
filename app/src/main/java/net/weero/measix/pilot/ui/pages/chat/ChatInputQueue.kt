package net.weero.measix.pilot.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ArtifactInputPreview
import net.weero.measix.pilot.service.ConversationInputQueueUiModel
import net.weero.measix.pilot.service.ImageSource
import net.weero.measix.pilot.service.InputQueuePauseUiReason
import net.weero.measix.pilot.service.QueuedInputUiModel
import net.weero.measix.pilot.service.QueuedInputUiPhase
import net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog
import net.weero.measix.pilot.utils.logDiagnosticFailure
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import kotlin.uuid.Uuid

@Composable
internal fun PendingInputDeletionDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(stringResource(R.string.chat_queue_delete_title)) },
        text = { Text(stringResource(R.string.chat_queue_delete_confirm, count)) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) {
            Text(stringResource(R.string.history_page_delete))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) {
            Text(stringResource(R.string.cancel))
        } },
    )
}

/** The caller owns commands and previews; this component retains only expansion and operation UI state. */
@Composable
internal fun ChatInputQueue(
    model: ConversationInputQueueUiModel,
    previews: suspend (Uuid, Long) -> Map<String, ArtifactInputPreview>,
    onRemove: suspend (Uuid, Long) -> Unit,
    onResume: suspend () -> Unit,
    onClear: suspend (Map<Uuid, Long>) -> Unit,
    onEdit: (Uuid, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model.items.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var confirmResume by remember { mutableStateOf(false) }
    var resuming by remember { mutableStateOf(false) }
    var clearSelection by remember { mutableStateOf<Map<Uuid, Long>?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()
    val resume: () -> Unit = {
        if (!resuming) {
            resuming = true
            failure = null
            scope.launch {
                try {
                    onResume()
                    confirmResume = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logDiagnosticFailure("ChatInputQueue", "Queue resume failed", error)
                    failure = error
                } finally { resuming = false }
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.chat_queue_count, model.queueCount))
            }
            TextButton(onClick = {
                failure = null
                clearSelection = model.items.associate { it.id to it.version }
            }, enabled = !clearing && model.items.none { it.phase == QueuedInputUiPhase.SUBMITTING }) {
                Text(stringResource(R.string.chat_queue_clear))
            }
            if (model.pauseReason != null) TextButton(
                onClick = { confirmResume = true },
                enabled = !resuming && model.items.all { it.phase == QueuedInputUiPhase.WAITING },
            ) { Text(stringResource(R.string.chat_queue_resume)) }
        }
        model.pauseReason?.let { reason ->
            Text(stringResource(when (reason) {
                InputQueuePauseUiReason.USER_STOP -> R.string.chat_queue_paused_stop
                InputQueuePauseUiReason.REALM_CHANGED -> R.string.chat_queue_paused_realm
                InputQueuePauseUiReason.CONTEXT_CHANGED -> R.string.chat_queue_paused_context
                InputQueuePauseUiReason.REQUEST_FAILED -> R.string.chat_queue_paused_failure
            }), style = MaterialTheme.typography.bodySmall)
        }
        failure?.let { DiagnosticDisclosure(it.userVisibleDiagnostic()) }
        Text(stringResource(R.string.chat_queue_process_notice), style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                model.items.forEach { item -> key(item.id, item.version) {
                    QueuedInputRow(item, previews, onRemove, onEdit)
                } }
            }
        }
    }
    if (confirmResume) AlertDialog(
        onDismissRequest = { if (!resuming) confirmResume = false },
        title = { Text(stringResource(R.string.chat_queue_resume)) },
        text = { Column {
            Text(stringResource(R.string.chat_queue_resume_context))
            failure?.let { DiagnosticDisclosure(it.userVisibleDiagnostic()) }
        } },
        confirmButton = { TextButton(onClick = resume, enabled = !resuming) { Text(stringResource(R.string.chat_queue_resume)) } },
        dismissButton = { TextButton(onClick = { confirmResume = false }, enabled = !resuming) { Text(stringResource(R.string.cancel)) } },
    )
    clearSelection?.let { selected ->
        val changed = model.items.associate { it.id to it.version } != selected ||
            model.items.any { it.phase == QueuedInputUiPhase.SUBMITTING }
        AlertDialog(
            onDismissRequest = { if (!clearing) clearSelection = null },
            title = { Text(stringResource(R.string.chat_queue_clear)) },
            text = { Column {
                Text(stringResource(R.string.chat_queue_clear_confirm, selected.size))
                if (changed) Text(stringResource(R.string.chat_queue_changed))
                failure?.let { DiagnosticDisclosure(it.userVisibleDiagnostic()) }
            } },
            confirmButton = { TextButton(enabled = !clearing && !changed, onClick = {
                clearing = true
                failure = null
                scope.launch {
                    try {
                        onClear(selected)
                        clearSelection = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        logDiagnosticFailure("ChatInputQueue", "Queue clear failed", error)
                        failure = error
                    } finally { clearing = false }
                }
            }) { Text(stringResource(R.string.chat_queue_clear)) } },
            dismissButton = { TextButton(enabled = !clearing, onClick = { clearSelection = null }) {
                Text(stringResource(R.string.cancel))
            } },
        )
    }
}

@Composable
private fun QueuedInputRow(
    item: QueuedInputUiModel,
    previews: suspend (Uuid, Long) -> Map<String, ArtifactInputPreview>,
    onRemove: suspend (Uuid, Long) -> Unit,
    onEdit: (Uuid, Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var attachments by remember { mutableStateOf<Map<String, ArtifactInputPreview>?>(null) }
    var selectedImage by remember { mutableStateOf<ImageSource?>(null) }
    var readAttempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val text = remember(item.parts) { item.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text } }
    val attachmentCount = item.parts.count { it is UIMessagePart.Image || it is UIMessagePart.Audio || it is UIMessagePart.Video || it is UIMessagePart.Document }
    LaunchedEffect(expanded, item.id, item.version, readAttempt) {
        if (!expanded || attachmentCount == 0) return@LaunchedEffect
        attachments = null
        try {
            attachments = previews(item.id, item.version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logDiagnosticFailure("ChatInputQueue", "Queued input preview failed", error)
            failure = error
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                Text(text.ifBlank { stringResource(R.string.chat_queue_attachments, attachmentCount) },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onEdit(item.id, item.version) }, enabled = item.canEdit && !removing) {
                Text(stringResource(R.string.edit))
            }
            TextButton(onClick = {
                if (!removing) {
                    removing = true
                    failure = null
                    scope.launch {
                        try { onRemove(item.id, item.version) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            logDiagnosticFailure("ChatInputQueue", "Queue removal failed", error)
                            failure = error
                        } finally { removing = false }
                    }
                }
            }, enabled = item.canRemove && !removing) { Text(stringResource(R.string.chat_queue_remove)) }
        }
        Text(stringResource(when (item.phase) {
            QueuedInputUiPhase.WAITING -> R.string.chat_queue_waiting
            QueuedInputUiPhase.EDITING -> R.string.chat_queue_editing
            QueuedInputUiPhase.SUBMITTING -> R.string.chat_queue_submitting
        }), style = MaterialTheme.typography.labelSmall)
        if (expanded) {
            if (text.isNotBlank()) SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
            if (attachmentCount > 0 && attachments == null && failure == null) CircularProgressIndicator(Modifier.size(20.dp))
            attachments?.values?.forEach { preview ->
                Text(preview.displayName, style = MaterialTheme.typography.bodySmall)
                preview.image?.let { image ->
                    AsyncImage(model = image, contentDescription = preview.displayName,
                        modifier = Modifier.size(72.dp).clickable { selectedImage = image },
                        onError = { state -> failure = state.result.throwable })
                }
            }
        }
        failure?.let {
            DiagnosticDisclosure(it.userVisibleDiagnostic())
            if (expanded && attachmentCount > 0) TextButton(onClick = { failure = null; readAttempt++ }) {
                Text(stringResource(R.string.application_recovery_retry))
            }
        }
    }
    selectedImage?.let { image ->
        ImagePreviewDialog(listOf(image), onDismissRequest = { selectedImage = null })
    }
}
