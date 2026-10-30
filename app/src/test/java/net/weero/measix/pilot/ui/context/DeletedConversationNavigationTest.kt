package net.weero.measix.pilot.ui.context

import androidx.navigation3.runtime.NavKey
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConversationDeletionReceipt
import net.weero.measix.pilot.service.ConversationOpenRequest
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DeletedConversationNavigationTest {
    @Test fun `repeated back callbacks keep the root without waiting for recomposition`() {
        val stack = mutableListOf<NavKey>(Screen.Enterprise, Screen.History)
        val navigator = Navigator(stack)
        repeat(3) { navigator.popBackStack() }
        assertEquals(listOf(Screen.Enterprise), stack)
    }

    @Test fun `history replacement preserves its current page and touches only the deleted original Session`() {
        val id = Uuid.random()
        val assistant = ConfigurationReference.random()
        val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("dep_navigation"), "user")
        val access = RealmAccess.Enterprise(scope, "original")
        val original = Screen.Chat(ConversationOpenRequest.OpenExisting(id, access))
        val otherSession = Screen.Chat(ConversationOpenRequest.OpenExisting(id, RealmAccess.Enterprise(scope, "replacement")))
        val otherId = Screen.Chat(ConversationOpenRequest.OpenExisting(Uuid.random(), access))
        val stack = mutableListOf<NavKey>(original, otherSession, otherId, Screen.History)
        val next = ConversationOpenRequest.NewDraft(Uuid.random(), access, assistant)
        val nav = Navigator(stack)
        nav.replaceDeletedConversation(ConversationDeletionReceipt(id, assistant, RealmSelection(access, 1)), next)
        assertEquals(listOf(Screen.Chat(next), otherSession, otherId, Screen.History), stack)
        nav.popBackStack()
        assertEquals(otherId, stack.last())
    }

    @Test fun `history return keeps the original assistant request for normal Draft validation`() {
        val request = ConversationOpenRequest.OpenExisting(Uuid.random(), RealmAccess.Personal)
        val stack = mutableListOf<NavKey>(Screen.Chat(request), Screen.History)
        val nav = Navigator(stack)
        val assistant = ConfigurationReference.random()
        val next = ConversationOpenRequest.NewDraft(Uuid.random(), request.access, assistant)
        nav.replaceDeletedConversation(ConversationDeletionReceipt(request.id, assistant, RealmSelection(request.access, 1)), next)
        nav.popBackStack()
        assertEquals(Screen.Chat(next), stack.last())
        assertFalse(nav.isCurrentConversation(request))
    }
}
