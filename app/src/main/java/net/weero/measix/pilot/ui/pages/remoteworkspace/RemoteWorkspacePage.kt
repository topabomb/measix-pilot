package net.weero.measix.pilot.ui.pages.remoteworkspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceStatus
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceSummary
import net.weero.measix.pilot.ui.components.files.FileBrowserPage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun remoteWorkspaceStatusText(status: RemoteWorkspaceStatus): String = stringResource(when (status) {
    RemoteWorkspaceStatus.CHECKING -> R.string.remote_workspace_checking
    RemoteWorkspaceStatus.FAILED -> R.string.remote_workspace_failed
    RemoteWorkspaceStatus.AVAILABLE -> R.string.remote_workspace_available
    RemoteWorkspaceStatus.UNPROVISIONED -> R.string.remote_workspace_unprovisioned
    RemoteWorkspaceStatus.CONNECTING -> R.string.remote_workspace_connecting
    RemoteWorkspaceStatus.RESTORING -> R.string.remote_workspace_restoring
    RemoteWorkspaceStatus.DISCONNECTING -> R.string.remote_workspace_disconnecting
    RemoteWorkspaceStatus.DISCONNECTED -> R.string.remote_workspace_disconnected
    RemoteWorkspaceStatus.DISABLED -> R.string.remote_workspace_disabled
    RemoteWorkspaceStatus.DELETING -> R.string.remote_workspace_deleting
    RemoteWorkspaceStatus.DELETED -> R.string.remote_workspace_deleted
    RemoteWorkspaceStatus.NEEDS_ATTENTION -> R.string.remote_workspace_attention
    RemoteWorkspaceStatus.FILES_UNAVAILABLE -> R.string.remote_workspace_unavailable
    RemoteWorkspaceStatus.READ_FAILED -> R.string.remote_workspace_read_failed
})

@Composable
internal fun RemoteWorkspaceCard(summary: RemoteWorkspaceSummary, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("remote-workspace-card")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.remote_workspace_title), style = MaterialTheme.typography.titleSmall)
                Text(if (summary.canOpenFiles) stringResource(R.string.remote_workspace_description) else remoteWorkspaceStatusText(summary.status),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (summary.canOpenFiles) Text(remoteWorkspaceStatusText(summary.status), style = MaterialTheme.typography.labelSmall)
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(20.dp))
        }
    }
}

internal fun EntryProviderScope<NavKey>.remoteWorkspaceEntry() {
    entry<Screen.RemoteWorkspace>(clazzContentKey = { it.id }) { key -> RemoteWorkspacePage(key.selection) }
}

@Composable
internal fun RemoteWorkspacePage(selection: RealmSelection?, vm: RemoteWorkspaceVM = koinViewModel { parametersOf(selection) }) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    FileBrowserPage(vm, stringResource(R.string.remote_workspace_title),
        diagnostic = summary?.let { listOfNotNull(it.reason, "MCP: ${it.mcpAvailable}; ${it.mcpReason.orEmpty()}", it.diagnostic).joinToString("\n") },
        status = { summary?.takeIf { it.selection == selection }?.let { Text(remoteWorkspaceStatusText(it.status)) } },
    )
}
