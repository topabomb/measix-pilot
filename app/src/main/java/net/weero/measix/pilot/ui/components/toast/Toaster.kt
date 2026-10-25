package net.weero.measix.pilot.ui.components.toast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.R

/** Draw last in the owning window's Box; no Popup, full-window touch handler or input forwarding. */
@Composable
fun Toaster(state: ToasterState, modifier: Modifier = Modifier, alignment: Alignment = Alignment.TopCenter) {
    var details by remember(state) { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(Modifier.widthIn(max = 480.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.items.asReversed().forEach { item -> key(item.id) {
                var overflow by remember(item) { mutableStateOf(false) }
                val colors = MaterialTheme.colorScheme
                net.weero.measix.pilot.ui.components.ui.DismissibleNotice({ state.dismiss(item.id) }) {
                    Surface(
                        color = when (item.type) {
                            ToastType.Error -> colors.errorContainer
                            ToastType.Warning -> colors.tertiaryContainer
                            ToastType.Success -> colors.secondaryContainer
                            else -> colors.surfaceContainerHigh
                        },
                        shape = MaterialTheme.shapes.large,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                            Text(item.message, style = MaterialTheme.typography.bodyMedium, maxLines = 4,
                                overflow = TextOverflow.Ellipsis, onTextLayout = { overflow = it.hasVisualOverflow })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (overflow) TextButton(onClick = { details = item.message }) {
                                    Text(stringResource(R.string.toast_details))
                                }
                                TextButton(onClick = { state.dismiss(item.id) }) { Text(stringResource(R.string.toast_close)) }
                            }
                        }
                    }
                }
            } }
        }
    }
    details?.let { message ->
        net.weero.measix.pilot.ui.components.ui.ErrorDetails(
            remember(message) { net.weero.measix.pilot.service.ChatError(title = null, detail = message) },
            onDismiss = { details = null },
        )
    }
}
