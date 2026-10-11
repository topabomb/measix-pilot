package net.weero.measix.pilot.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavConnectionChange
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.WebDavConfigurationReadState
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun SettingWebDavPage(vm: WebDavSettingsVM = koinViewModel()) {
    val state by vm.configuration.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val tested by vm.tested.collectAsStateWithLifecycle()
    val available = state as? WebDavConfigurationReadState.Available
    var deleting by remember(available?.selection) { mutableStateOf<WebDavConnection?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.webdav_settings_title)) }, navigationIcon = { BackButton() },
            actions = {
                if (available?.selection?.access != RealmAccess.Personal && available?.resources?.isNotEmpty() == true) {
                    IconButton(onClick = vm::refresh, enabled = !busy) {
                        Icon(HugeIcons.Refresh, contentDescription = stringResource(R.string.enterprise_refresh_status))
                    }
                }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (available?.selection?.access == RealmAccess.Personal) {
                item { Text(stringResource(R.string.webdav_settings_description), style = MaterialTheme.typography.bodyMedium) }
            }
            when (val value = state) {
                WebDavConfigurationReadState.Loading -> item { CircularProgressIndicator() }
                is WebDavConfigurationReadState.Unavailable -> item { DiagnosticDisclosure(value.detail) }
                is WebDavConfigurationReadState.Available -> {
                    if (value.selection.access != RealmAccess.Personal) {
                        item {
                            Text(stringResource(R.string.webdav_enterprise_managed), Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (value.resources.isEmpty()) item { Text(stringResource(R.string.webdav_enterprise_not_configured)) }
                        items(value.resources, key = { it.id }) { resource ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(resource.name, style = MaterialTheme.typography.titleMedium)
                                    if (resource.id == value.currentId) Text(stringResource(R.string.webdav_current), style = MaterialTheme.typography.bodySmall)
                                    if (resource.available && !resource.writable) Text(stringResource(R.string.mcp_enterprise_read_only), style = MaterialTheme.typography.bodySmall)
                                    if (!resource.available) {
                                        Text(stringResource(R.string.remote_workspace_unavailable), style = MaterialTheme.typography.bodySmall)
                                        resource.detail?.let { DiagnosticDisclosure(it) }
                                    }
                                }
                            }
                        }
                    } else {
                        item { Button(onClick = { vm.edit(null) }, enabled = !busy) { Text(stringResource(R.string.webdav_add_connection)) } }
                        if (value.current == null) item { Text(stringResource(R.string.webdav_no_current)) }
                        items(value.personalConnections, key = { it.id.toString() }) { connection ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().selectable(
                                    selected = value.personalCurrentId == connection.id, enabled = !busy, role = Role.RadioButton,
                                    onClick = { vm.change(WebDavConnectionChange.Select(connection.id)) },
                                ).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    RadioButton(selected = value.personalCurrentId == connection.id, onClick = null, enabled = !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text(connection.name, style = MaterialTheme.typography.titleMedium)
                                        Text(connection.rootUrl, style = MaterialTheme.typography.bodySmall)
                                        if (value.personalCurrentId == connection.id) Text(stringResource(R.string.webdav_current))
                                    }
                                }
                                Row(Modifier.padding(horizontal = 8.dp)) {
                                    TextButton(onClick = { vm.edit(connection) }, enabled = !busy) { Text(stringResource(R.string.edit)) }
                                    TextButton(onClick = { deleting = connection }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                                }
                            }
                        }
                    }
                }
            }
            if (error != null && draft == null) item { DiagnosticDisclosure(error!!) }
        }
    }
    deleting?.let { connection ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.webdav_delete_connection, connection.name)) },
            confirmButton = { TextButton(onClick = {
                vm.change(WebDavConnectionChange.Delete(connection.id)); deleting = null
            }, enabled = !busy) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
    draft?.let { pending ->
        AlertDialog(onDismissRequest = vm::dismissDraft,
            title = { Text(stringResource(if (pending.expected == null) R.string.webdav_add_connection else R.string.edit)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pending.connection.name, { value -> vm.updateDraft { it.copy(name = value) } },
                        label = { Text(stringResource(R.string.webdav_connection_name)) }, enabled = !busy, singleLine = true)
                    OutlinedTextField(pending.connection.rootUrl, { value -> vm.updateDraft { it.copy(rootUrl = value) } },
                        label = { Text(stringResource(R.string.webdav_root_url)) }, enabled = !busy, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(pending.connection.username, { value -> vm.updateDraft { it.copy(username = value) } },
                        label = { Text(stringResource(R.string.backup_page_username)) }, enabled = !busy, singleLine = true)
                    OutlinedTextField(pending.connection.password, { value -> vm.updateDraft { it.copy(password = value) } },
                        label = { Text(stringResource(R.string.backup_page_password)) }, enabled = !busy, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    TextButton(onClick = vm::test, enabled = !busy) { Text(stringResource(R.string.backup_page_test_connection)) }
                    if (tested) Text(stringResource(R.string.backup_page_connection_success))
                    error?.let { DiagnosticDisclosure(it) }
                }
            },
            confirmButton = { TextButton(onClick = vm::save, enabled = !busy) { Text(stringResource(R.string.webdav_save)) } },
            dismissButton = { TextButton(onClick = vm::dismissDraft) { Text(stringResource(R.string.cancel)) } })
    }
}
