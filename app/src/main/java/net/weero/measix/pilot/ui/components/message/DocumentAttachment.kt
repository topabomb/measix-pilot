package net.weero.measix.pilot.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import net.weero.measix.pilot.R

/** The same attachment card is used for user documents and downloaded tool files. */
@Composable
internal fun DocumentAttachment(part: UIMessagePart.Document, onOpen: () -> Unit) {
    Surface(tonalElevation = 2.dp, onClick = onOpen, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (part.mime) {
                "application/pdf" -> Icon(painterResource(R.drawable.pdf), null, Modifier.size(20.dp))
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    Icon(painterResource(R.drawable.docx), null, Modifier.size(20.dp))
                else -> Icon(HugeIcons.File02, null, Modifier.size(20.dp))
            }
            Text(part.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
