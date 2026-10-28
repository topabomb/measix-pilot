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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.ArrowLeft01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavConnectionChange
import net.weero.measix.pilot.data.configuration.WebDavConnectionField
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
    val validationError by vm.validationError.collectAsStateWithLifecycle()
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
                is WebDavConfigurationReadState.Unavailable -> item {
                    DiagnosticDisclosure(value.detail)
                    TextButton(onClick = vm::refresh, enabled = !busy) {
                        Text(stringResource(R.string.application_recovery_retry))
                    }
                }
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
        Dialog(onDismissRequest = vm::dismissDraft, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(Modifier.fillMaxSize().testTag("webdav_connection_editor"), topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (pending.expected == null) R.string.webdav_add_connection else R.string.edit)) },
                    navigationIcon = { IconButton(onClick = vm::dismissDraft) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.cancel))
                    } },
                    actions = { TextButton(onClick = vm::save, enabled = !busy) { Text(stringResource(R.string.webdav_save)) } },
                )
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                    .padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(pending.connection.rootUrl, { value -> vm.updateDraft { it.copy(rootUrl = value) } },
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("webdav_address"),
                        label = { Text(stringResource(R.string.webdav_root_url)) }, enabled = !busy, singleLine = true,
                        placeholder = { Text("https://files.example.com/dav/") },
                        isError = validationError?.field == WebDavConnectionField.ADDRESS,
                        supportingText = { Text(stringResource(if (validationError?.field == WebDavConnectionField.ADDRESS)
                            R.string.webdav_invalid_url else R.string.webdav_address_help)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text(stringResource(R.string.webdav_credentials_help), Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(pending.connection.username, { value -> vm.updateDraft { it.copy(username = value) } },
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("webdav_username"),
                        label = { Text(stringResource(R.string.backup_page_username)) }, enabled = !busy, singleLine = true,
                        isError = validationError?.field == WebDavConnectionField.USERNAME,
                        supportingText = if (validationError?.field == WebDavConnectionField.USERNAME)
                            ({ Text(stringResource(R.string.webdav_invalid_username)) }) else null)
                    OutlinedTextField(pending.connection.password, { value -> vm.updateDraft { it.copy(password = value) } },
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("webdav_password"),
                        label = { Text(stringResource(R.string.backup_page_password)) }, enabled = !busy, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(pending.connection.name, { value -> vm.updateDraft { it.copy(name = value) } },
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("webdav_name"),
                        label = { Text(stringResource(R.string.webdav_connection_name)) }, enabled = !busy, singleLine = true,
                        supportingText = { Text(stringResource(R.string.webdav_name_help)) })
                    OutlinedButton(onClick = vm::test, enabled = !busy,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("webdav_test_connection")) {
                        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(R.string.backup_page_test_connection))
                    }
                    if (tested) Text(stringResource(R.string.backup_page_connection_success), color = MaterialTheme.colorScheme.primary)
                    (state as? WebDavConfigurationReadState.Unavailable)?.let { unavailable ->
                        DiagnosticDisclosure(unavailable.detail)
                        TextButton(onClick = vm::refresh, enabled = !busy) {
                            Text(stringResource(R.string.application_recovery_retry))
                        }
                    }
                    error?.let { detail ->
                        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.remote_workspace_operation_failed), color = MaterialTheme.colorScheme.error)
                            Text(detail.lineSequence().first(), style = MaterialTheme.typography.bodySmall,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            DiagnosticDisclosure(detail)
                        }
                    }
                }
            }
        }
    }
}
