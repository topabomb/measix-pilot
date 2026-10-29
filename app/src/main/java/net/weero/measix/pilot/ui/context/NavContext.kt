package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import net.weero.measix.pilot.Screen

class Navigator(private val backStack: MutableList<NavKey>) {
    internal val currentEntry: NavKey? get() = backStack.lastOrNull()

    fun isCurrentConversation(request: net.weero.measix.pilot.service.ConversationOpenRequest): Boolean =
        (backStack.lastOrNull() as? Screen.Chat)?.request == request

    /** Replace only deleted chat entries from the original principal; history remains in place. */
    internal fun replaceDeletedConversation(
        receipt: net.weero.measix.pilot.service.ConversationDeletionReceipt,
        continuation: net.weero.measix.pilot.service.ConversationContinuation,
    ) {
        backStack.indices.forEach { index ->
            val chat = backStack[index] as? Screen.Chat ?: return@forEach
            if (chat.request.id == receipt.conversationId && chat.request.access == receipt.selection.access) {
                backStack[index] = continuation.request?.let { Screen.Chat(it) } ?: Screen.Enterprise
            }
        }
    }

    fun navigate(screen: Screen, launchSingleTop: Boolean = false) {
        if (launchSingleTop && backStack.lastOrNull() == screen) return

        backStack.add(screen)
    }

    /** File links are distinct visits, even A -> B -> A; bound their saved viewer state. */
    internal fun navigateWorkspaceFile(screen: Screen.WorkspaceFileEditor) {
        while (backStack.count { it is Screen.WorkspaceFileEditor } >= 16) {
            backStack.removeAt(backStack.indexOfFirst { it is Screen.WorkspaceFileEditor })
        }
        backStack.add(screen.copy(visitId = java.util.UUID.randomUUID().toString()))
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
