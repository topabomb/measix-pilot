package net.weero.measix.pilot.ui.pages.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.service.ConversationAssistantTarget
import net.weero.measix.pilot.service.imageannotation.ImageAnnotationRaster
import net.weero.measix.pilot.service.imageannotation.prepareImageAnnotationPreview
import net.weero.measix.pilot.service.imageannotation.renderImageAnnotationPng
import net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure
import net.weero.measix.pilot.ui.components.ui.ImageAnnotationDialog
import net.weero.measix.pilot.ui.hooks.ChatInputState
import net.weero.measix.pilot.utils.userVisibleDiagnostic

/** The chat owns authorization and the edit lease; the dialog owns only transient drawing state. */
@Composable
internal fun ChatImageAnnotation(
    vm: ChatVM,
    target: ConversationAssistantTarget,
    input: ChatInputState,
    image: ChatInputState.ImageEditTarget,
    onDismiss: () -> Unit,
) {
    val originalTarget = remember(image) { target }
    var edit by remember(image) { mutableStateOf<ChatVM.ImageAnnotationInput?>(null) }
    var raster by remember(image) { mutableStateOf<ImageAnnotationRaster?>(null) }
    var failure by remember(image) { mutableStateOf<Throwable?>(null) }
    var gif by remember(image) { mutableStateOf(false) }
    var attempt by remember(image) { mutableStateOf(0) }
    DisposableEffect(image) { onDispose { edit?.close() } }
    LaunchedEffect(image, target) {
        if (target != originalTarget) {
            edit?.close()
            onDismiss()
        }
    }
    LaunchedEffect(image) {
        snapshotFlow { input.isImageEditCurrent(image) }.first { !it }
        edit?.close()
        onDismiss()
    }
    LaunchedEffect(image, attempt) {
        failure = null
        var opened: ChatVM.ImageAnnotationInput? = null
        try {
            opened = vm.beginImageAnnotation(originalTarget, input, image)
            if (ImageMime.sniff(opened.bytes) == "image/gif") {
                gif = true
            } else {
                val prepared = prepareImageAnnotationPreview(opened.bytes)
                currentCoroutineContext().ensureActive()
                edit = opened
                raster = prepared
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("ChatImageAnnotation", "Image annotation preparation failed", error)
            failure = error
        } finally {
            if (edit !== opened) opened?.close()
        }
    }
    fun dismiss() {
        edit?.close()
        onDismiss()
    }
    val prepared = raster
    val original = edit
    if (prepared != null && original != null) {
        ImageAnnotationDialog(
            raster = prepared,
            onDismissRequest = ::dismiss,
            onSave = { strokes ->
                if (strokes.isNotEmpty()) {
                    val png = renderImageAnnotationPng(original.bytes, strokes)
                    check(vm.saveImageAnnotation(original, png)) { "image_annotation_input_changed" }
                }
                dismiss()
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(stringResource(R.string.image_annotation_title)) },
            text = {
                when {
                    gif -> Text(stringResource(R.string.image_annotation_gif_unavailable))
                    failure != null -> DiagnosticDisclosure(requireNotNull(failure).userVisibleDiagnostic())
                    else -> CircularProgressIndicator()
                }
            },
            confirmButton = {
                if (failure != null) TextButton(onClick = { attempt++ }) {
                    Text(stringResource(R.string.application_recovery_retry))
                }
            },
            dismissButton = { TextButton(onClick = ::dismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
