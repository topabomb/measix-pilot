package net.weero.measix.pilot.ui.pages.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.data.configuration.WebDavBackupPreferences
import net.weero.measix.pilot.service.webdav.WebDavBackupItem
import net.weero.measix.pilot.service.webdav.WebDavBackupService
import net.weero.measix.pilot.service.webdav.WebDavBackupTarget
import net.weero.measix.pilot.data.sync.S3BackupItem
import net.weero.measix.pilot.data.sync.S3Sync
import net.weero.measix.pilot.data.sync.s3.S3Config
import net.weero.measix.pilot.utils.UiState
import android.net.Uri
import java.io.File
import net.weero.measix.pilot.service.BackupRestoreApplicationService

class BackupVM internal constructor(
    private val settingsStore: SettingsStore,
    private val webDav: WebDavBackupService,
    private val s3Sync: S3Sync,
    private val restoreApplicationService: BackupRestoreApplicationService,
    private val queries: ConfigurationQueryService,
    private val commands: ConfigurationApplicationService,
) : ViewModel() {
    val settings = settingsStore.userSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    internal val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    internal val webDavConfiguration = queries.observeWebDavConfiguration()
        .stateIn(viewModelScope, SharingStarted.Eagerly, WebDavConfigurationReadState.Loading)
    internal val webDavIssue = MutableStateFlow<Throwable?>(null)
    internal val webDavReady = MutableStateFlow(false)
    private val target = MutableStateFlow<WebDavBackupTarget?>(null)
    internal val webDavTargetIdentity = target.asStateFlow()
    private val retryWebDav = MutableStateFlow(0L)
    private var listJob: Job? = null
    private var s3ListJob: Job? = null
    private var s3ListRevision = 0L
    private var s3ListedConfig: S3Config? = null

    init {
        viewModelScope.launch {
            webDavConfiguration.map { it as? WebDavConfigurationReadState.Available }
                .distinctUntilChangedBy { value -> Triple(value?.selection, value?.backup,
                    value?.resources?.singleOrNull { it.id.toString() == value.backup.resourceId }?.copy(name = "")) }
                .combine(retryWebDav) { available, _ -> available }
                .collectLatest { available ->
                    val old = target.value
                    target.value = null
                    webDavReady.value = false
                    webDavBackupItems.value = UiState.Idle
                    webDavIssue.value = null
                    listJob?.cancel()
                    if (old != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { webDav.close(old) }
                    currentCoroutineContext().ensureActive()
                    if (available?.backup?.resourceId == null) return@collectLatest
                    var acquired: WebDavBackupTarget? = null
                    try {
                        val target = webDav.open(available.selection, available.backup).also { acquired = it }
                        this@BackupVM.target.value = target
                        acquired = null
                        webDavReady.value = true
                        loadBackupFileItems()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { webDavIssue.value = error }
                    finally { acquired?.let(webDav::closeLater) }
                }
        }
        viewModelScope.launch {
            var initial = true
            settingsStore.userSettings.map { it.s3Config }.distinctUntilChanged().collect {
                s3ListJob?.cancel()
                ++s3ListRevision
                s3ListedConfig = null
                s3BackupItems.value = UiState.Idle
                if (initial) {
                    initial = false
                    loadS3BackupFileItems()
                }
            }
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.updateLocal(transform = transform)
        }
    }

    fun retryWebDavConnection() { retryWebDav.value += 1 }

    fun loadBackupFileItems() {
        val target = target.value ?: return
        listJob?.cancel()
        listJob = viewModelScope.launch {
            try {
                webDavBackupItems.value = UiState.Loading
                val items = webDav.list(target)
                if (this@BackupVM.target.value === target) webDavBackupItems.value = UiState.Success(items)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (this@BackupVM.target.value === target) webDavBackupItems.value = UiState.Error(error)
            }
        }
    }

    internal suspend fun saveWebDavPreferences(view: WebDavConfigurationReadState.Available, proposed: WebDavBackupPreferences) {
        commands.changeWebDavBackup(view.selection, view.backup, proposed)
    }

    private fun requireWebDavTarget() = checkNotNull(target.value) { "webdav_backup_resource_unavailable" }

    suspend fun testWebDav() = webDav.test(requireWebDavTarget())

    suspend fun backup() {
        webDav.backup(requireWebDavTarget())
        recordBackupTime()
    }

    internal suspend fun restore(item: WebDavBackupItem) {
        check(item.target === target.value) { "webdav_backup_target_changed" }
        restoreApplicationService.restoreWebDav(item)
    }

    internal suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        check(item.target === target.value) { "webdav_backup_target_changed" }
        webDav.delete(item)
    }

    suspend fun exportToFile(): File {
        val file = restoreApplicationService.exportLocal()
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalUri(uri: Uri) = restoreApplicationService.restoreLocal(uri)

    override fun onCleared() { target.value?.let(webDav::closeLater) }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        val config = settingsStore.userSettings.value.s3Config
        val revision = ++s3ListRevision
        s3ListJob?.cancel()
        s3ListedConfig = null
        s3ListJob = viewModelScope.launch {
            try {
                s3BackupItems.value = UiState.Loading
                val items = s3Sync.listBackupFiles(config)
                currentCoroutineContext().ensureActive()
                if (revision == s3ListRevision && settingsStore.userSettings.value.s3Config == config) {
                    s3ListedConfig = config
                    s3BackupItems.value = UiState.Success(items)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision == s3ListRevision && settingsStore.userSettings.value.s3Config == config) {
                    s3BackupItems.value = UiState.Error(error)
                }
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        restoreApplicationService.restoreS3(config = requireS3ItemConfig(item), item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(requireS3ItemConfig(item), item)
    }

    private fun requireS3ItemConfig(item: S3BackupItem): S3Config {
        val config = s3ListedConfig
        val items = (s3BackupItems.value as? UiState.Success)?.data
        check(config != null && config == settingsStore.userSettings.value.s3Config && items?.any { it === item } == true) {
            "s3_backup_target_changed"
        }
        return config
    }

    private suspend fun recordBackupTime() {
        settingsStore.updateLocal { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}
