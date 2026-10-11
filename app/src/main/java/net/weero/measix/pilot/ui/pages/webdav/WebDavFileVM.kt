package net.weero.measix.pilot.ui.pages.webdav

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.WebDavConfigurationReadState
import net.weero.measix.pilot.service.webdav.WebDavBrowserService
import net.weero.measix.pilot.ui.components.files.FileBrowserVM

internal class WebDavFileVM private constructor(
    private val davSource: WebDavBrowserService.Source,
    queries: ConfigurationQueryService,
    context: Context,
) : FileBrowserVM(davSource, context) {
    constructor(selection: RealmSelection?, queries: ConfigurationQueryService, browser: WebDavBrowserService, context: Context) :
        this(browser.source(selection), queries, context)

    val configuration = queries.observeWebDavConfiguration()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WebDavConfigurationReadState.Loading)
    val targetId = davSource.targetId
    private val _switching = MutableStateFlow(false)
    val switching = _switching.asStateFlow()

    fun choose(id: String) {
        if (_switching.value || targetId.value == id) return
        _switching.value = true
        viewModelScope.launch {
            try { resetTarget { davSource.choose(id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { _switching.value = false }
        }
    }
}
