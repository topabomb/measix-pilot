package net.weero.measix.pilot.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.ui.components.ui.DocumentExportActions

class PromptVM(
    private val settingsStore: SettingsStore,
    private val exports: MediaExportService,
) : ViewModel() {
    private val _lockedChanges = MutableSharedFlow<SettingsLockedException>(extraBufferCapacity = 1)
    val lockedChanges = _lockedChanges.asSharedFlow()
    val settings = settingsStore.userSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    internal val documentExport = DocumentExportActions(exports, viewModelScope)

    internal suspend fun prepareExport(snapshot: PromptInjection.ModeInjection) =
        exports.prepareModeInjectionExport(snapshot)

    override fun onCleared() {
        documentExport.close()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            try {
                settingsStore.updateLocal(transform = transform)
            } catch (error: SettingsLockedException) {
                _lockedChanges.emit(error)
            }
        }
    }
}
