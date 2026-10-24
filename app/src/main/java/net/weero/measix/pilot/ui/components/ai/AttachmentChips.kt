package net.weero.measix.pilot.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import net.weero.measix.pilot.service.ArtifactDraftScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import net.weero.measix.pilot.ui.hooks.ChatInputState
import net.weero.measix.pilot.utils.userVisibleDiagnostic

private data class InputImagePreview(
    val targets: List<ChatInputState.ImageEditTarget>,
    val sources: List<net.weero.measix.pilot.service.ImageSource>,
    val initialIndex: Int,
)

@Composable
internal fun MediaFileInputRow(
    state: ChatInputState,
    artifactDraftScope: ArtifactDraftScope,
    readPreviews: (suspend (List<UIMessagePart>) -> Map<String, net.weero.measix.pilot.service.ArtifactInputPreview>)? = null,
    onAnnotateImage: ((ChatInputState.ImageEditTarget) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val parts = state.messageContent
    var imagePreview by remember(state, artifactDraftScope) { mutableStateOf<InputImagePreview?>(null) }
    var failure by remember(artifactDraftScope) { mutableStateOf<Throwable?>(null) }
    var readAttempt by remember(artifactDraftScope) { mutableStateOf(0) }
    val inputRevision by artifactDraftScope.inputRevision.collectAsState()
    val previews by produceState(emptyMap<String, net.weero.measix.pilot.service.ArtifactInputPreview>(), artifactDraftScope, parts, inputRevision, readAttempt) {
        value = emptyMap()
        failure = null
        value = try {
            readPreviews?.invoke(parts) ?: artifactDraftScope.describeInputs(parts)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("AttachmentChips", "Attachment names unavailable", error)
            failure = error
            emptyMap()
        }
    }

    val displayNameByUri = remember(previews) { previews.mapValues { it.value.displayName } }
    val imageTargets = state.imageEditTargets()
    imagePreview?.let { preview ->
        androidx.compose.runtime.LaunchedEffect(preview) {
            androidx.compose.runtime.snapshotFlow { preview.targets.all(state::isImageEditCurrent) }.first { !it }
            imagePreview = null
        }
        net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog(
            images = preview.sources,
            initialIndex = preview.initialIndex,
            onDismissRequest = { imagePreview = null },
            onEditImage = onAnnotateImage?.let { annotate -> { index ->
                preview.targets.getOrNull(index)?.takeIf(state::isImageEditCurrent)?.let(annotate)
                imagePreview = null
            } },
        )
    }

    fun removePart(part: UIMessagePart, url: String, imageTarget: ChatInputState.ImageEditTarget? = null) {
        if (imageTarget != null) {
            if (!state.removeImage(imageTarget)) return
        } else state.messageContent = state.messageContent.filterNot { it == part }
        val stillUsed = state.messageContent.any { remaining ->
            when (remaining) {
                is UIMessagePart.Image -> remaining.url
                is UIMessagePart.Video -> remaining.url
                is UIMessagePart.Audio -> remaining.url
                is UIMessagePart.Document -> remaining.url
                else -> null
            } == url
        }
        if (!stillUsed && state.shouldDeleteFileOnRemove(part)) {
            scope.launch {
                try { artifactDraftScope.discard(url.toUri()) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("AttachmentChips", "Attachment removal failed", error)
                    failure = error
                }
            }
        }
    }

    Column {
    failure?.let {
        net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure(it.userVisibleDiagnostic())
        androidx.compose.material3.TextButton(onClick = { readAttempt++ }) {
            Text(androidx.compose.ui.res.stringResource(net.weero.measix.pilot.R.string.application_recovery_retry))
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        var imageIndex = 0
        state.messageContent.fastForEach { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    val selectedTarget = imageTargets[imageIndex++]
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "image",
                            displayNameByUri = displayNameByUri
                        ),
                        leading = {
                            Surface(
                                modifier = Modifier.size(34.dp).clickable(enabled = previews[part.url]?.image != null) {
                                    val entries = imageTargets.mapNotNull { target ->
                                        previews[target.image.url]?.image?.let { target to it }
                                    }
                                    val index = entries.indexOfFirst { it.first === selectedTarget }
                                    if (index >= 0) imagePreview = InputImagePreview(entries.map { it.first }, entries.map { it.second }, index)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                key(inputRevision) {
                                    AsyncImage(
                                        model = previews[part.url]?.image,
                                        onError = { failure = it.result.throwable },
                                        contentDescription = attachmentNameFromUrl(part.url, "image", displayNameByUri),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        },
                        onRemove = { removePart(part, part.url, selectedTarget) }
                    )
                }

                is UIMessagePart.Video -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "video",
                            displayNameByUri = displayNameByUri
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Audio -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "audio",
                            displayNameByUri = displayNameByUri
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Document -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = part.fileName,
                            displayNameByUri = displayNameByUri
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                else -> Unit
            }
        }
    }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: ImageVector,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByUri: Map<String, String>,
): String {
    displayNameByUri[url]?.let { return it }
    val parsed = runCatching { url.toUri() }.getOrNull()
    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        return storedFileName
    }

    return fallback
}
