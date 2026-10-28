package net.weero.measix.pilot.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavConnectionChange
import net.weero.measix.pilot.data.configuration.WebDavConnectionValidationException
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.WebDavConfigurationReadState
import net.weero.measix.pilot.utils.logDiagnosticFailure
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal data class WebDavConnectionDraft(
    val selection: RealmSelection,
    val expected: WebDavConnection?,
    val connection: WebDavConnection,
)

internal class WebDavSettingsVM(
    private val queries: ConfigurationQueryService,
    private val commands: ConfigurationApplicationService,
    private val webDav: net.weero.measix.pilot.service.webdav.WebDavApplicationService,
) : ViewModel() {
    private val _configuration = MutableStateFlow<WebDavConfigurationReadState>(WebDavConfigurationReadState.Loading)
    val configuration = _configuration.asStateFlow()
    val draft = MutableStateFlow<WebDavConnectionDraft?>(null)
    val error = MutableStateFlow<String?>(null)
    val validationError = MutableStateFlow<WebDavConnectionValidationException?>(null)
    val busy = MutableStateFlow(false)
    val tested = MutableStateFlow(false)
    private var operation: Job? = null
    private var configurationJob: Job? = null
    private var selectedRealm: RealmSelection? = null

    init {
        viewModelScope.launch {
            queries.observeSelectedRealmSelection().collect { selection ->
                selectedRealm = selection
                if (draft.value?.selection?.let { it != selection } == true ||
                    (_configuration.value as? WebDavConfigurationReadState.Available)?.selection?.let { it != selection } == true) {
                    dismissDraft()
                }
            }
        }
        observeConfiguration()
    }

    private fun observeConfiguration() {
        configurationJob?.cancel()
        _configuration.value = WebDavConfigurationReadState.Loading
        configurationJob = viewModelScope.launch {
            queries.observeWebDavConfiguration().collect { value ->
                val before = (_configuration.value as? WebDavConfigurationReadState.Available)?.selection
                val after = (value as? WebDavConfigurationReadState.Available)?.selection
                if (after != null && (draft.value?.selection?.let { it != after } == true || before?.let { it != after } == true)) {
                    dismissDraft()
                }
                _configuration.value = value
            }
        }
    }

    fun refresh() {
        if (busy.value) return
        val current = configuration.value as? WebDavConfigurationReadState.Available
        if (current == null) {
            observeConfiguration()
            return
        }
        busy.value = true
        error.value = null
        operation = viewModelScope.launch {
            try { webDav.refresh(current.selection) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                logDiagnosticFailure("WebDavSettings", "WebDAV status refresh failed", failure)
                if (selectedRealm == current.selection) {
                    error.value = failure.userVisibleDiagnostic()
                }
            } finally { busy.value = false }
        }
    }

    fun edit(connection: WebDavConnection?) {
        val current = configuration.value as? WebDavConfigurationReadState.Available ?: return
        if (current.selection != selectedRealm || current.selection.access != RealmAccess.Personal || busy.value) return
        error.value = null
        validationError.value = null
        tested.value = false
        draft.value = WebDavConnectionDraft(current.selection, connection,
            connection ?: WebDavConnection(name = "", rootUrl = ""))
    }

    fun updateDraft(transform: (WebDavConnection) -> WebDavConnection) {
        if (busy.value) return
        error.value = null
        validationError.value = null
        tested.value = false
        draft.value = draft.value?.let { it.copy(connection = transform(it.connection)) }
    }

    fun dismissDraft() {
        operation?.cancel()
        draft.value = null
        error.value = null
        validationError.value = null
        tested.value = false
    }

    fun save() {
        val pending = draft.value ?: return
        if (busy.value) return
        val connection = validate(pending.connection) ?: return
        submit(pending.selection, WebDavConnectionChange.Save(connection, pending.expected), closeEditor = true)
    }

    fun test() {
        val pending = draft.value ?: return
        if (busy.value) return
        val connection = validate(pending.connection) ?: return
        busy.value = true
        operation = viewModelScope.launch {
            try {
                webDav.testConnection(pending.selection, connection)
                if (draft.value == pending) tested.value = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: WebDavConnectionValidationException) {
                if (draft.value == pending) validationError.value = failure
            }
            catch (failure: Exception) {
                logDiagnosticFailure("WebDavSettings", "WebDAV connection test failed", failure)
                if (draft.value == pending) error.value = failure.userVisibleDiagnostic()
            } finally { busy.value = false }
        }
    }

    fun change(change: WebDavConnectionChange) {
        val current = configuration.value as? WebDavConfigurationReadState.Available ?: return
        submit(current.selection, change, closeEditor = false)
    }

    private fun validate(connection: WebDavConnection): WebDavConnection? {
        error.value = null
        validationError.value = null
        tested.value = false
        return try { connection.validated() }
        catch (failure: WebDavConnectionValidationException) {
            validationError.value = failure
            null
        }
    }

    private fun submit(selection: RealmSelection, change: WebDavConnectionChange, closeEditor: Boolean) {
        if (busy.value) return
        busy.value = true
        error.value = null
        operation = viewModelScope.launch {
            try {
                commands.changeWebDavConnection(selection, change)
                if (selectedRealm == selection && closeEditor) {
                    draft.value = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: WebDavConnectionValidationException) {
                if (selectedRealm == selection) {
                    if (closeEditor) validationError.value = failure else error.value = failure.userVisibleDiagnostic()
                }
            }
            catch (failure: Exception) {
                logDiagnosticFailure("WebDavSettings", "WebDAV configuration could not be saved", failure)
                if (selectedRealm == selection) {
                    error.value = failure.userVisibleDiagnostic()
                }
            } finally { busy.value = false }
        }
    }
}
