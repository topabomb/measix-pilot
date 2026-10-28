package net.weero.measix.pilot.ui.context

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.weero.measix.pilot.ui.components.toast.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationOpenRequest
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationAssistantUnavailableException
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.utils.logDiagnosticFailure
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

class ChatNavigation internal constructor(
    private val create: (ConfigurationReference?, String?, List<Uri>) -> Unit,
    private val open: (Uuid, Uuid?) -> Unit,
) {
    fun newChat(assistantId: ConfigurationReference? = null, initText: String? = null, initFiles: List<Uri> = emptyList()) =
        create(assistantId, initText, initFiles)

    fun existingChat(chatId: Uuid, nodeId: Uuid? = null) = open(chatId, nodeId)
}

/** Click handlers retain the rendered realm; delayed navigation never recaptures a newer session. */
@Composable
fun rememberChatNavigation(
    navigator: Navigator = LocalNavController.current,
    renderedSelection: net.weero.measix.pilot.data.enterprise.RealmSelection? = null,
    draftFolder: Pair<net.weero.measix.pilot.service.ConversationFolderAccess, Uuid>? = null,
): ChatNavigation {
    val query = koinInject<ConversationQueryService>()
    val application = koinInject<ConversationApplicationService>()
    val configuration = koinInject<ConfigurationQueryService>()
    val selection by remember(query) { query.observeCurrentSelection() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val failure = stringResource(R.string.error_title_operation)
    val capturedSelection = renderedSelection ?: selection
    return remember(navigator, capturedSelection, draftFolder, application, configuration, scope, toaster, failure) {
        ChatNavigation(
            create = { assistant, text, files ->
                val original = capturedSelection
                val sourceEntry = navigator.currentEntry
                if (original == null) toaster.show(failure, type = ToastType.Error) else scope.launch {
                    if (navigator.currentEntry !== sourceEntry) return@launch
                    try {
                        val request = try {
                            application.newDraftRequest(original, assistant, draftFolder)
                        } catch (error: ConversationAssistantUnavailableException) {
                            if (assistant != null || original.access !is RealmAccess.Enterprise) throw error
                            configuration.requireSelection(original)
                            if (navigator.currentEntry !== sourceEntry) return@launch
                            navigator.clearAndNavigate(Screen.Enterprise)
                            return@launch
                        }
                        configuration.requireSelection(original)
                        if (navigator.currentEntry !== sourceEntry) return@launch
                        navigator.clearAndNavigate(Screen.Chat(request, text, files.map(Uri::toString)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        logDiagnosticFailure("ChatNavigation", "New conversation request failed", error)
                        try {
                            configuration.requireSelection(original)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            return@launch
                        }
                        if (navigator.currentEntry !== sourceEntry) return@launch
                        toaster.show("$failure\n${error.userVisibleDiagnostic()}", type = ToastType.Error)
                    }
                }
            },
            open = { id, node ->
                val original = capturedSelection
                val sourceEntry = navigator.currentEntry
                if (original == null) toaster.show(failure, type = ToastType.Error) else scope.launch {
                    if (navigator.currentEntry !== sourceEntry) return@launch
                    try {
                        configuration.requireSelection(original)
                        if (navigator.currentEntry !== sourceEntry) return@launch
                        navigator.clearAndNavigate(Screen.Chat(ConversationOpenRequest.OpenExisting(id, original.access), nodeId = node?.toString()))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        logDiagnosticFailure("ChatNavigation", "Existing conversation navigation rejected", error)
                    }
                }
            },
        )
    }
}
