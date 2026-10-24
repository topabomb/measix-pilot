package net.weero.measix.pilot.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ModelCatalogUiModel
import net.weero.measix.pilot.service.ModelContextCapacityUiModel
import net.weero.measix.pilot.ui.components.ai.ModelContextLengthField
import net.weero.measix.pilot.ui.components.ai.validModelContextLengthInput
import net.weero.measix.pilot.ui.components.ai.configurationUnavailableText
import net.weero.measix.pilot.ui.components.ui.CardGroup
import net.weero.measix.pilot.utils.userVisibleDiagnostic

@Composable
internal fun EnterpriseModelContextCapacitySettings(catalog: ModelCatalogUiModel, vm: ModelSettingsVM) {
    val selection = requireNotNull(catalog.selection)
    // Changing realm/session discards the old editor; the command still validates its captured identity.
    key(selection) {
        var open by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<ModelContextCapacityUiModel?>(null) }
        CardGroup {
            item(onClick = { open = true },
                headlineContent = { Text(stringResource(R.string.model_context_capacity)) },
                supportingContent = { Text(stringResource(R.string.model_context_capacity_local)) })
        }
        if (open && editing == null) AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.model_context_capacity)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(catalog.contextCapacities, key = { it.reference.toString() }) { item ->
                        ListItem(modifier = Modifier.clickable { editing = item },
                            headlineContent = { Text(item.name) },
                            supportingContent = { Text(item.unavailableReason?.let { configurationUnavailableText(it) }
                                ?: item.tokens?.toString() ?: stringResource(R.string.model_context_capacity_unknown)) })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
        editing?.let { initial ->
            key(initial.reference) {
                var input by remember { mutableStateOf(initial.tokens?.toString().orEmpty()) }
                var saving by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                fun save(tokens: Int?) {
                    saving = true
                    scope.launch {
                        try {
                            vm.setContextCapacity(selection, initial.reference, initial.tokens, tokens)
                            editing = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            android.util.Log.e("ModelContextCapacity", "Saving model capacity failed", failure)
                            error = failure.userVisibleDiagnostic()
                        } finally {
                            saving = false
                        }
                    }
                }
                AlertDialog(
                    onDismissRequest = { if (!saving) editing = null },
                    title = { Text(initial.name) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModelContextLengthField(input, { input = it }, stringResource(R.string.model_context_capacity_local),
                                enabled = !saving && initial.unavailableReason == null)
                            initial.unavailableReason?.let { Text(configurationUnavailableText(it), color = MaterialTheme.colorScheme.error) }
                            error?.let { androidx.compose.foundation.text.selection.SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
                            if (initial.tokens != null) TextButton(enabled = !saving, onClick = { save(null) }) {
                                Text(stringResource(R.string.configuration_restore_default))
                            }
                        }
                    },
                    confirmButton = { TextButton(enabled = !saving && initial.unavailableReason == null && validModelContextLengthInput(input),
                        onClick = { save(input.toIntOrNull()) }) { Text(stringResource(R.string.confirm)) } },
                    dismissButton = { TextButton(enabled = !saving, onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}
