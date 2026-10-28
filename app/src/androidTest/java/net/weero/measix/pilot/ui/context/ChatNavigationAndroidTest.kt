package net.weero.measix.pilot.ui.context

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationAssistantUnavailableException
import net.weero.measix.pilot.service.ConversationOpenRequest
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.ui.components.toast.Toaster
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatNavigationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val contexts = mutableListOf<org.koin.core.KoinApplication>()
    @After fun close() { contexts.forEach { it.close() } }

    @Test fun missingEnterpriseDefaultOffersSpaceSelection() {
        val f = Fixture()
        coEvery { f.application.newDraftRequest(f.original, null, null) } throws
            ConversationAssistantUnavailableException("conversation_assistant_missing")
        f.show()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { f.stack == listOf(Screen.Enterprise) }
        coVerify(exactly = 1) { f.configuration.requireSelection(f.original) }
    }

    @Test fun explicitUnavailableAssistantKeepsTheRouteAndDiagnostic() {
        val f = Fixture()
        val assistant = ConfigurationReference.Enterprise(f.authority, "asd_revoked")
        coEvery { f.application.newDraftRequest(f.original, assistant, null) } throws
            ConversationAssistantUnavailableException("conversation_assistant_unavailable")
        f.show(assistant)
        compose.onNodeWithText("Create").performClick()
        compose.onNodeWithText("conversation_assistant_unavailable", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(Screen.History), f.stack) }
    }

    @Test fun delayedDraftCannotNavigateAfterSelectionRoundTrip() {
        val f = Fixture()
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val draft = ConversationOpenRequest.NewDraft(Uuid.random(), f.original.access,
            ConfigurationReference.Enterprise(f.authority, "asd_assistant"))
        coEvery { f.application.newDraftRequest(f.original, null, null) } coAnswers {
            requested.complete(Unit)
            release.await()
            draft
        }
        f.show()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { requested.isCompleted }
        compose.runOnIdle {
            f.selection.value = RealmSelection(RealmAccess.Personal, 2)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            f.selection.value = f.original.copy(revision = 3)
            release.complete(Unit)
        }
        compose.waitUntil(5_000) { f.rejected > 0 }
        compose.runOnIdle { assertEquals(listOf(Screen.History), f.stack) }
    }

    @Test fun delayedExistingChatCannotNavigateAfterSelectionRoundTrip() {
        val f = Fixture()
        f.show()
        lateinit var originalNavigation: ChatNavigation
        compose.runOnIdle {
            originalNavigation = requireNotNull(f.renderedNavigation)
            f.selection.value = RealmSelection(RealmAccess.Personal, 2)
        }
        compose.waitForIdle()
        compose.runOnIdle { f.selection.value = f.original.copy(revision = 3) }
        compose.waitForIdle()
        // The drawer retains this callback while its close animation suspends.
        compose.runOnIdle { originalNavigation.existingChat(Uuid.random()) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.History), f.stack) }
    }

    @Test fun existingChatInTheOriginalSelectionPreservesItsConversationAndNode() {
        val f = Fixture()
        f.show()
        val conversation = Uuid.random()
        val node = Uuid.random()
        compose.runOnIdle { requireNotNull(f.renderedNavigation).existingChat(conversation, node) }
        compose.waitUntil(5_000) { f.stack.lastOrNull() is Screen.Chat }
        compose.runOnIdle {
            assertEquals(listOf(Screen.Chat(ConversationOpenRequest.OpenExisting(conversation, f.original.access),
                nodeId = node.toString())), f.stack)
        }
        coVerify(exactly = 1) { f.configuration.requireSelection(f.original) }
    }

    @Test fun existingChatCannotReplaceTheRouteOpenedWhileSelectionValidationWaits() {
        val f = Fixture()
        f.show()
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.configuration.requireSelection(f.original) } coAnswers {
            requested.complete(Unit)
            release.await()
        }
        compose.runOnIdle { requireNotNull(f.renderedNavigation).existingChat(Uuid.random()) }
        compose.waitUntil(5_000) { requested.isCompleted }
        compose.runOnIdle {
            f.stack.add(Screen.Favorite)
            release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.History, Screen.Favorite), f.stack) }
    }

    @Test fun newChatCannotReplaceTheRouteOpenedWhileDraftCreationWaits() {
        val f = Fixture()
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val draft = ConversationOpenRequest.NewDraft(Uuid.random(), f.original.access,
            ConfigurationReference.Enterprise(f.authority, "asd_assistant"))
        coEvery { f.application.newDraftRequest(f.original, null, null) } coAnswers {
            requested.complete(Unit)
            release.await()
            draft
        }
        f.show()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { requested.isCompleted }
        compose.runOnIdle {
            f.stack.add(Screen.Favorite)
            release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(Screen.History, Screen.Favorite), f.stack) }
    }

    private inner class Fixture {
        val authority = EnterpriseAuthority("navigation")
        val original = RealmSelection(RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "empty-user"), "session"), 1)
        val selection = MutableStateFlow<RealmSelection?>(original)
        val stack = mutableListOf<NavKey>(Screen.History)
        val application = mockk<ConversationApplicationService>()
        val configuration = mockk<ConfigurationQueryService>()
        private val query = mockk<ConversationQueryService>()
        @Volatile var rejected = 0
        var renderedNavigation: ChatNavigation? = null

        fun show(assistant: ConfigurationReference? = null) {
            every { query.observeCurrentSelection() } returns selection
            coEvery { configuration.requireSelection(any()) } coAnswers {
                if (firstArg<RealmSelection>() != selection.value) {
                    rejected++
                    error("enterprise_source_changed")
                }
            }
            val isolated = koinApplication { modules(module {
                single { application }
                single { configuration }
                single { query }
            }) }.also { contexts += it }
            val navigator = Navigator(stack)
            compose.setContent {
                KoinIsolatedContext(isolated) {
                    MaterialTheme {
                        val toaster = rememberToasterState()
                        CompositionLocalProvider(LocalNavController provides navigator, LocalToaster provides toaster) {
                            val navigation = rememberChatNavigation()
                            SideEffect { renderedNavigation = navigation }
                            Button(onClick = { navigation.newChat(assistant) }) { Text("Create") }
                            Toaster(toaster)
                        }
                    }
                }
            }
            compose.waitForIdle()
        }
    }
}
