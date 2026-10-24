package net.weero.measix.pilot.ui.pages.setting

import net.weero.measix.pilot.ui.components.ui.longPressReorder
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.weero.measix.pilot.ui.components.toast.ToastType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.components.ui.AutoAIIcon
import net.weero.measix.pilot.ui.components.ui.Tag
import net.weero.measix.pilot.ui.components.ui.TagType
import net.weero.measix.pilot.ui.components.ui.decodeProviderSetting
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.utils.ImageUtils
import net.weero.measix.pilot.utils.logDiagnosticFailure
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.ui.pages.setting.components.providerTypeNameRes
import net.weero.measix.pilot.utils.plus
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.launch

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var searchQuery by remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()
    var draftProvider by remember { mutableStateOf<ProviderSetting?>(null) }
    var draftSaving by remember { mutableStateOf(false) }
    var draftError by remember { mutableStateOf<String?>(null) }
    var pendingScrollId by remember { mutableStateOf<ConfigurationReference?>(null) }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (searchQuery.isNotBlank()) return@rememberReorderableLazyListState
        val visibleProviders = settings.providers
        val fromId = visibleProviders.getOrNull(from.index)?.id ?: return@rememberReorderableLazyListState
        val toId = visibleProviders.getOrNull(to.index)?.id ?: return@rememberReorderableLazyListState
        vm.updateSettings { current ->
            val fromIndex = current.providers.indexOfFirst { it.id == fromId }
            val toIndex = current.providers.indexOfFirst { it.id == toId }
            if (fromIndex < 0 || toIndex < 0) {
                current
            } else {
                current.copy(
                    providers = current.providers.toMutableList().apply {
                        add(toIndex, removeAt(fromIndex))
                    }
                )
            }
        }
    }

    val filteredProviders = remember(settings.providers, searchQuery) {
        if (searchQuery.isBlank()) {
            settings.providers
        } else {
            settings.providers.filter { provider ->
                provider.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(pendingScrollId, filteredProviders.map { it.id }) {
        val target = pendingScrollId ?: return@LaunchedEffect
        val index = filteredProviders.indexOfFirst { it.id == target }
        if (index >= 0) {
            lazyListState.animateScrollToItem(index)
            pendingScrollId = null
        }
    }
    draftProvider?.let { draft ->
        ProviderCreationPage(draft, draftSaving, draftError,
            onCancel = { if (!draftSaving) { draftProvider = null; draftError = null } },
            onSave = { edited ->
                draftProvider = edited
                draftSaving = true
                draftError = null
                scope.launch {
                    try {
                        pendingScrollId = vm.createProvider(edited)
                        searchQuery = ""
                        draftProvider = null
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        logDiagnosticFailure("ProviderSettings", "Provider creation failed", failure)
                        draftError = failure.userVisibleDiagnostic()
                    } finally {
                        draftSaving = false
                    }
                }
            })
        return
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.setting_provider_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    ImportProviderButton {
                        pendingScrollId = vm.importProvider(it)
                        searchQuery = ""
                    }
                    AddButton { draftProvider = it; draftError = null }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.setting_provider_page_search_providers)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(HugeIcons.Cancel01, contentDescription = null)
                            }
                    }
                },
                singleLine = true,
                shape = CircleShape,
            )


            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) +
                    PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                items(filteredProviders, key = { it.id.toString() }) { provider ->
                    ReorderableItem(
                        state = reorderableState,
                        key = provider.id.toString()
                    ) { isDragging ->
                        ProviderItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(longPressReorder(isDragging, enabled = searchQuery.isBlank())),
                            provider = provider,
                            onClick = {
                                navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportProviderButton(
    onAdd: suspend (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val importSuccess by rememberUpdatedState(stringResource(R.string.setting_provider_page_import_success))
    val noQrFound by rememberUpdatedState(stringResource(R.string.setting_provider_page_no_qr_found))
    var showImportDialog by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }

    val queries = org.koin.compose.koinInject<net.weero.measix.pilot.service.ConfigurationQueryService>()
    val selection by remember(queries) { queries.observeSelectedRealmSelection() }.collectAsStateWithLifecycle(initialValue = null)
    var scannerSelection by remember { mutableStateOf<net.weero.measix.pilot.data.enterprise.RealmSelection?>(null) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    fun performImport(load: suspend () -> ProviderSetting?) {
        if (importing) return
        importing = true
        importError = null
        scope.launch {
            try {
                val provider = load() ?: return@launch
                onAdd(provider)
                showPasteDialog = false
                toaster.show(importSuccess, type = ToastType.Success)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logDiagnosticFailure("ProviderSettings", "Provider import failed", failure)
                importError = failure.userVisibleDiagnostic()
            } finally {
                importing = false
            }
        }
    }
    scannerSelection?.let { original ->
        net.weero.measix.pilot.ui.components.ui.QRCodeScannerDialog(
            selection = original, queries = queries,
            onDismissRequest = { scannerSelection = null },
            onResult = { content ->
                scannerSelection = null
                if (selection == original) performImport { decodeProviderSetting(content) }
            },
        )
    }
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) performImport {
            val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ImageUtils.decodeQRCodeFromUri(context, uri)
            }
            if (content.isNullOrEmpty()) {
                toaster.show(noQrFound, type = ToastType.Error)
                null
            } else decodeProviderSetting(content)
        }
    }

    IconButton(
        enabled = !importing,
        onClick = {
            showImportDialog = true
        }
    ) {
        Icon(HugeIcons.FileImport, null)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 主要操作：扫描二维码
                        Button(
                            onClick = {
                                showImportDialog = false
                                scannerSelection = selection
                            },
                            enabled = selection != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Camera01,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 次要操作：从相册选择
                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                pickImageLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Image02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 第三操作：粘贴配置字符串
                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                showPasteDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.FileImport,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_paste_config),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }

    if (showPasteDialog) {
        ProviderPasteImportDialog(
            busy = importing,
            onDismiss = { if (!importing) showPasteDialog = false },
            onImport = { text -> performImport { decodeProviderSetting(text.trim()) } },
        )
    }
    importError?.let { diagnostic ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.setting_provider_page_import_dialog_title)) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(diagnostic, modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { importError = null }) { Text(stringResource(R.string.confirm)) } },
        )
    }
}

@Composable
private fun AddButton(onAdd: (ProviderSetting) -> Unit) {
    var choosingType by remember { mutableStateOf(false) }
    IconButton(onClick = { choosingType = true }) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_provider_page_add_provider))
    }
    if (choosingType) AlertDialog(
        onDismissRequest = { choosingType = false },
        title = { Text(stringResource(R.string.setting_provider_page_add_provider)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderSetting.Types.forEach { type ->
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        val draft = when (type) {
                            ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI()
                            ProviderSetting.Google::class -> ProviderSetting.Google()
                            ProviderSetting.Claude::class -> ProviderSetting.Claude()
                            else -> error("unsupported_provider_type")
                        }
                        choosingType = false
                        onAdd(draft)
                    }) { Text(stringResource(providerTypeNameRes(type))) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosingType = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun ProviderCreationPage(draft: ProviderSetting, saving: Boolean, error: String?,
    onCancel: () -> Unit, onSave: (ProviderSetting) -> Unit) {
    BackHandler { if (!saving) onCancel() }
    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.setting_provider_page_add_provider)) },
                navigationIcon = {
                    androidx.compose.material3.FilledTonalIconButton(enabled = !saving, onClick = onCancel) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                    }
                }, colors = CustomColors.topBarColors)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (saving) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            } else SettingProviderConfigPage(
                provider = draft, providerSettingsState = ProviderSettingsUiState(provider = draft),
                onOpenConnectionTest = {}, onDismissConnectionTest = {}, onSelectConnectionModel = {},
                onRunConnectionTest = {}, onEdit = onSave, onDelete = {}, isDraft = true, operationError = error,
            )
        }
    }
}

@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (provider.enabled) {
                CustomColors.listItemColors.containerColor
            } else MaterialTheme.colorScheme.errorContainer,
        ),
        onClick = {
            onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = provider.name,
                modifier = Modifier.size(40.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)) {
                        provider.shortDescription()
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = if (provider.enabled) TagType.SUCCESS else TagType.WARNING) {
                        Text(stringResource(if (provider.enabled) R.string.setting_provider_page_enabled else R.string.setting_provider_page_disabled))
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderPasteImportDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_provider_page_paste_config)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ai-provider:v1:...") },
                singleLine = false,
                minLines = 3,
                maxLines = 6
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = !busy && text.isNotBlank()
            ) {
                Text(stringResource(R.string.setting_provider_page_import_dialog_title))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
