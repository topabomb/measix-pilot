package net.weero.measix.pilot.ui.pages.assistant

import me.rerere.common.configuration.ConfigurationReference
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.service.AssistantDeletionPreview
import net.weero.measix.pilot.utils.logDiagnosticFailure
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.service.MemoryService
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.ArtifactUseCase

internal data class PendingAssistantDeletionConfirmation(val assistant: Assistant, val preview: AssistantDeletionPreview)

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryService: MemoryService,
    private val assistantManagementService: AssistantManagementService,
    private val artifactUseCase: ArtifactUseCase,
) : ViewModel() {
    internal var pendingDeletion by mutableStateOf<PendingAssistantDeletionConfirmation?>(null)
        private set
    var deletionBusy by mutableStateOf(false)
        private set
    var deletionFailure by mutableStateOf<String?>(null)
        private set
    var deletionNeedsReview by mutableStateOf(false)
        private set
    var deletionCleanupPending by mutableStateOf(false)
        private set
    private val _lockedChanges = MutableSharedFlow<SettingsLockedException>(extraBufferCapacity = 1)
    val lockedChanges = _lockedChanges.asSharedFlow()
    val settings: StateFlow<Settings> = settingsStore.userSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun reorderAssistants(orderedIds: List<ConfigurationReference>) {
        updateSettings {
            artifactUseCase.updateSettingsReferences { current ->
                val requestedIds = orderedIds.toSet()
                val assistantsById = current.assistants.associateBy { it.id }
                current.copy(
                    assistants = orderedIds.mapNotNull(assistantsById::get) +
                        current.assistants.filter { it.id !in requestedIds },
                )
            }
        }
    }

    fun reorderAssistantTags(orderedIds: List<ConfigurationReference>) {
        updateSettings {
            artifactUseCase.updateSettingsReferences { current ->
                val requestedIds = orderedIds.toSet()
                val tagsById = current.assistantTags.associateBy { it.id }
                current.copy(
                    assistantTags = orderedIds.mapNotNull(tagsById::get) +
                        current.assistantTags.filter { it.id !in requestedIds },
                )
            }
        }
    }

    fun addAssistant(assistant: Assistant) {
        updateSettings {
            artifactUseCase.updateSettingsReferences { current ->
                current.copy(
                    assistants = current.assistants.plus(assistant),
                )
            }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        if (deletionBusy) return
        deletionCommand {
            pendingDeletion = PendingAssistantDeletionConfirmation(assistant, assistantManagementService.prepareDeletion(assistant.id))
        }
    }

    fun dismissDeletion() { if (!deletionBusy) pendingDeletion = null }
    fun dismissDeletionFailure() { deletionFailure = null; deletionCleanupPending = false }

    internal fun confirmDeletion(expected: PendingAssistantDeletionConfirmation) {
        if (deletionBusy || pendingDeletion !== expected) return
        pendingDeletion = null
        deletionCommand {
            val result = assistantManagementService.deleteAssistant(expected.assistant.id, preview = expected.preview).getOrThrow()
            deletionCleanupPending = result.cleanupPending
        }
    }

    private fun deletionCommand(operation: suspend () -> Unit) {
        deletionBusy = true
        viewModelScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logDiagnosticFailure("AssistantVM", "Assistant deletion failed", error)
                deletionNeedsReview = error is net.weero.measix.pilot.data.datastore.AssistantManagementRejection &&
                    error.reason in setOf("assistant_pending_inputs_changed", "assistant_pending_inputs_confirmation_required")
                deletionFailure = error.userVisibleDiagnostic()
            } finally {
                deletionBusy = false
            }
        }
    }

    fun copyAssistant(assistant: Assistant) {
        updateSettings {
            val copiedAssistant = assistant.copy(
                id = ConfigurationReference.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
                // Clone 重置子助手授权：不继承全局可见和允许列表
                isSubAssistantGloballyVisible = false,
                allowedSubAssistantIds = emptySet(),
            )
            artifactUseCase.updateSettingsReferences { current ->
                current.copy(
                    assistants = current.assistants.plus(copiedAssistant),
                )
            }
        }
    }

    private fun updateSettings(update: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                update()
            } catch (error: SettingsLockedException) {
                _lockedChanges.emit(error)
            }
        }
    }

    fun getMemories(assistant: Assistant) = memoryService.observeCurrent(assistant.id).map { view ->
        view.records.map { net.weero.measix.pilot.data.model.AssistantMemory(it.id, it.content) }
    }
}
