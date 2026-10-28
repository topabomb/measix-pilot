package net.weero.measix.pilot.ui.pages.chat

import net.weero.measix.pilot.data.datastore.ConversationSortOrder
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.AssistantCatalogReadState
import net.weero.measix.pilot.data.enterprise.RealmSelection
import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.service.ConversationFolderAccess
import net.weero.measix.pilot.service.ConversationFolderDirectory
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationActivity
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerVM internal constructor(
    private val context: Application,
    configurationQueryService: ConfigurationQueryService,
    private val conversationQueryService: ConversationQueryService,
    private val conversationApplicationService: ConversationApplicationService,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    internal val webDavConfiguration = configurationQueryService.observeWebDavConfiguration()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), net.weero.measix.pilot.service.WebDavConfigurationReadState.Loading)

    internal val assistantCatalog = configurationQueryService.observeAssistantCatalog()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AssistantCatalogReadState.Loading)

    private val selectedFolder = MutableStateFlow<Pair<ConversationFolderAccess, Uuid>?>(null)
    private val assistantTarget = MutableStateFlow<ConversationFolderAccess?>(null)

    /** The visible conversation owns the drawer identity, independently of the next-chat default. */
    internal fun bindTarget(target: ConversationFolderAccess) {
        if (assistantTarget.value == target) return
        selectedFolder.value = null
        saveScrollPosition(0, 0)
        assistantTarget.value = target
    }

    val selectedFolderId: StateFlow<Uuid?> = combine(assistantTarget, selectedFolder) { target, selected ->
        selected?.takeIf { it.first == target }?.second
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val draftFolder = combine(assistantTarget, selectedFolder) { target, selected ->
        selected?.takeIf { it.first == target }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal suspend fun selectAssistant(selection: RealmSelection, assistantId: ConfigurationReference, createNew: Boolean) =
        conversationApplicationService.selectAssistantRequest(selection, assistantId, createNew)

    internal suspend fun moveToAssistant(conversation: ConversationSummary, assistantId: ConfigurationReference, selectForNewChats: Boolean) =
        conversationApplicationService.moveToAssistant(
            net.weero.measix.pilot.service.ConversationAssistantTarget(conversation.commandTarget, conversation.assistantId), assistantId, selectForNewChats)

    private val folderRefresh = MutableStateFlow(0)
    fun retryFolders() { folderRefresh.value += 1 }
    val folderDirectory: StateFlow<ConversationFolderDirectory?> = combine(assistantTarget, folderRefresh) { target, _ -> target }
        .flatMapLatest { if (it == null) flowOf(null) else conversationQueryService.foldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversationActivities: StateFlow<Map<Uuid, Set<ConversationActivity>>> =
        conversationQueryService.conversationActivities()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val sortRead = folderRefresh.flatMapLatest {
        conversationQueryService.observeSortOrder().map<ConversationSortOrder, Result<ConversationSortOrder>?> { Result.success(it) }
            .onStart { emit(null) }
            .catch { error ->
                if (error is CancellationException) throw error
                android.util.Log.e("ChatDrawerVM", "Conversation sort preference read failed", error)
                emit(Result.failure(error))
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sortFailure = sortRead.map { it?.exceptionOrNull()?.userVisibleDiagnostic() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sortOrder: StateFlow<ConversationSortOrder?> = sortRead.map { it?.getOrNull() }
         .onEach { sort ->
            if (sort == null) return@onEach
            val previous: String? = savedStateHandle["conversationSortOrder"]
            if (previous != null && previous != sort.name) saveScrollPosition(0, 0)
            savedStateHandle["conversationSortOrder"] = sort.name
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations: Flow<PagingData<ConversationListItem>> = sortOrder.flatMapLatest { sort ->
        if (sort == null) return@flatMapLatest flowOf(PagingData.empty<ConversationListItem>())
        assistantTarget.flatMapLatest { target ->
            if (target == null) flowOf(PagingData.empty<ConversationSummary>()) else selectedFolder
                .map { selected -> selected?.takeIf { it.first == target }?.second }
                .distinctUntilChanged()
                .flatMapLatest { folderId ->
                    if (folderId == null) conversationQueryService.unfiledPaging(target, sort)
                    else conversationQueryService.folderPaging(target, folderId, sort)
                }
        }
            .map { pagingData ->
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                        when {
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.sortTime
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.sortTime
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                } else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.sortTime
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.sortTime
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
        }.cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(access: ConversationFolderAccess?, folderId: Uuid?) {
        if (access == null || access != assistantTarget.value) return
        selectedFolder.value = folderId?.let { access to it }
    }

    suspend fun createFolder(access: ConversationFolderAccess, name: String) =
        conversationApplicationService.createFolder(access, name)

    suspend fun renameFolder(access: ConversationFolderAccess, folderId: Uuid, name: String) =
        conversationApplicationService.renameFolder(access, folderId, name)

    suspend fun deleteFolder(access: ConversationFolderAccess, folderId: Uuid) {
        conversationApplicationService.deleteFolder(access, folderId)
        if (selectedFolderId.value == folderId) selectedFolder.value = null
    }

    suspend fun moveConversationToFolder(access: ConversationFolderAccess, conversation: ConversationSummary, folderId: Uuid?) =
        conversationApplicationService.moveToFolder(access, conversation, folderId)

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
