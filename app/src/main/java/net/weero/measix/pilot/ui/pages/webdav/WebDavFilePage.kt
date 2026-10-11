package net.weero.measix.pilot.ui.pages.webdav

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.WebDavConfigurationReadState
import net.weero.measix.pilot.ui.components.files.FileBrowserPage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

internal fun EntryProviderScope<NavKey>.webDavFileEntry() {
    entry<Screen.WebDavFiles>(clazzContentKey = { it.id }) { key -> WebDavFilePage(key.selection) }
}

@Composable
internal fun WebDavFilePage(selection: RealmSelection?, vm: WebDavFileVM = koinViewModel { parametersOf(selection) }) {
    val configuration by vm.configuration.collectAsStateWithLifecycle()
    val target by vm.targetId.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val available = (configuration as? WebDavConfigurationReadState.Available)?.takeIf { it.selection == selection }
    var menu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(available?.selection, state.revoked) { menu = false; pending = null }
    FileBrowserPage(vm, stringResource(R.string.webdav_file_manager), titleContent = {
        Box {
            val name = available?.resources?.find { it.id == target }?.name ?: stringResource(R.string.webdav_no_current)
            val resources = available?.resources.orEmpty()
            val canChoose = resources.size > 1 || resources.any { it.id != target }
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .then(if (canChoose) Modifier.clickable(enabled = !switching, role = Role.Button) { menu = true } else Modifier),
                verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.webdav_file_manager), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (canChoose) Icon(HugeIcons.ArrowDown01, null, Modifier.size(16.dp))
                }
                if (switching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                available?.resources?.forEach { connection ->
                    DropdownMenuItem(enabled = connection.available, text = { Text(connection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { RadioButton(selected = target == connection.id, onClick = null) },
                        onClick = {
                            menu = false
                            if (connection.id != target) {
                                if (state.running) pending = connection.id else vm.choose(connection.id)
                            }
                        })
                }
            }
        }
    })
    pending?.let { id ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.webdav_switch_server)) },
            text = { Text(stringResource(R.string.webdav_switch_cancel_transfer)) },
            confirmButton = { TextButton({ pending = null; vm.choose(id) }) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton({ pending = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}
