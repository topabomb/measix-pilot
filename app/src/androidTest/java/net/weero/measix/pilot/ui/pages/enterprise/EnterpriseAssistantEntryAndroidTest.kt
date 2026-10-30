package net.weero.measix.pilot.ui.pages.enterprise

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantCatalogReadState
import net.weero.measix.pilot.service.AssistantCatalogUiModel
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationOpenRequest
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.toast.Toaster
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.pages.chat.ConversationUnavailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** An explicit stale choice uses the existing unavailable page and assistant picker. */
@RunWith(AndroidJUnit4::class)
class EnterpriseAssistantEntryAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val contexts = mutableListOf<org.koin.core.KoinApplication>()
    @After fun closeContexts() { contexts.forEach { it.close() } }

    @Test fun invalidSavedAssistantUsesTheExistingPickerOnlyWhenRequested() {
        val fixture = Fixture()
        fixture.show()
        verify(exactly = 0) { fixture.queries.observeAssistantCatalog() }
        coVerify(exactly = 0) { fixture.conversations.selectAssistantRequest(any(), any(), any()) }
        chooseAssistant()
        compose.onNodeWithText(text(R.string.configuration_reason_missing)).assertIsDisplayed()
        compose.onNodeWithText(fixture.assistant.name).performClick()
        compose.waitUntil(5_000) { fixture.backStack.lastOrNull() == Screen.Chat(fixture.draft) }
        assertEquals(listOf(Screen.Chat(fixture.draft)), fixture.backStack)
        coVerify(exactly = 1) { fixture.conversations.selectAssistantRequest(fixture.selection, fixture.assistant.id, true) }
    }

    @Test fun explicitFailureDoesNotRequireChoosingAnAssistantToOpenSpaces() {
        val fixture = Fixture()
        fixture.show()
        compose.onNodeWithTag("conversation-recovery-spaces").performClick()
        compose.runOnIdle { assertEquals(listOf(fixture.entry, Screen.Enterprise), fixture.backStack) }
        verify(exactly = 0) { fixture.queries.observeAssistantCatalog() }
        coVerify(exactly = 0) { fixture.conversations.selectAssistantRequest(any(), any(), any()) }
    }

    @Test fun dismissedPendingSelectionDoesNotReplaceTheSubsequentlyOpenedPage() {
        val fixture = Fixture()
        val started = AtomicBoolean()
        val completed = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.conversations.selectAssistantRequest(fixture.selection, fixture.assistant.id, true) } coAnswers {
            started.set(true)
            release.await()
            fixture.draft
        }
        coEvery { fixture.queries.requireSelection(fixture.selection) } coAnswers {
            if (release.isCompleted) completed.set(true)
            Unit
        }
        fixture.show()
        chooseAssistant()
        compose.onNodeWithText(fixture.assistant.name).performClick()
        compose.waitUntil(5_000) { started.get() }
        compose.onNodeWithContentDescription(text(R.string.update_card_close)).performClick()
        compose.onNodeWithTag("conversation-recovery-spaces").performClick()
        compose.runOnIdle { assertEquals(Screen.Enterprise, fixture.backStack.last()) }
        release.complete(Unit)
        compose.waitUntil(5_000) { completed.get() }
        compose.runOnIdle { assertEquals(listOf(fixture.entry, Screen.Enterprise), fixture.backStack) }
    }

    @Test fun catalogReadFailureKeepsDiagnosticsAndCanRetryTheOriginalPicker() {
        val fixture = Fixture()
        val subscriptions = AtomicInteger()
        every { fixture.queries.observeAssistantCatalog() } answers {
            if (subscriptions.incrementAndGet() == 1) {
                MutableStateFlow<AssistantCatalogReadState>(AssistantCatalogReadState.Unavailable("IOException: catalog unavailable"))
            } else fixture.catalog
        }
        fixture.show()
        chooseAssistant()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.chat_conversation_diagnostics)).fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithText(text(R.string.chat_conversation_diagnostics))[0].performClick()
        compose.onNodeWithText("IOException: catalog unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.update_card_close)).performClick()
        chooseAssistant()
        compose.onNodeWithText(fixture.assistant.name).assertIsDisplayed()
        assertEquals(2, subscriptions.get())
        coVerify(exactly = 0) { fixture.conversations.selectAssistantRequest(any(), any(), any()) }
    }

    @Test fun revokedSelectionCannotPublishTheCompletedDraftNavigation() {
        val fixture = Fixture()
        val started = AtomicBoolean()
        val revoked = AtomicBoolean()
        val verified = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.conversations.selectAssistantRequest(fixture.selection, fixture.assistant.id, true) } coAnswers {
            started.set(true)
            release.await()
            fixture.draft
        }
        coEvery { fixture.queries.requireSelection(fixture.selection) } coAnswers {
            if (revoked.get()) {
                verified.set(true)
                error("enterprise_selection_revoked")
            }
            Unit
        }
        fixture.show()
        chooseAssistant()
        compose.onNodeWithText(fixture.assistant.name).performClick()
        compose.waitUntil(5_000) { started.get() }
        revoked.set(true)
        release.complete(Unit)
        compose.waitUntil(5_000) { verified.get() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.chat_conversation_diagnostics)).fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithText(text(R.string.chat_conversation_diagnostics))[0].performClick()
        compose.onNodeWithText("enterprise_selection_revoked", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(fixture.entry), fixture.backStack) }
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun chooseAssistant() = compose.onNodeWithText(text(R.string.safe_mode_switch_assistant)).performClick()

    private inner class Fixture {
        private val authority = EnterpriseAuthority("deployment")
        private val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(authority, "user"), "entry-session")
        val selection = RealmSelection(access, 1)
        val assistant = Assistant(
            id = ConfigurationReference.Enterprise(authority, "asd_available"),
            name = "Available enterprise assistant",
        )
        val queries = mockk<ConfigurationQueryService>()
        val conversations = mockk<ConversationApplicationService>()
        val catalog = MutableStateFlow<AssistantCatalogReadState>(AssistantCatalogReadState.Available(
            AssistantCatalogUiModel(
                selection,
                ConfigurationSelection(ConfigurationReference.Enterprise(authority, "asd_missing"), ConfigurationUnavailableReason.REFERENCE_MISSING),
                mapOf(assistant.id to assistant),
                emptyList(),
            ),
        ))
        val draft = ConversationOpenRequest.NewDraft(Uuid.random(), access, assistant.id)
        val entry = Screen.Startup()
        val backStack = mutableListOf<NavKey>(entry)

        init {
            every { queries.observeAssistantCatalog() } returns catalog
            coEvery { queries.requireSelection(selection) } returns Unit
            coEvery { conversations.selectAssistantRequest(selection, assistant.id, true) } returns draft
        }

        fun show() {
            val navigator = Navigator(backStack)
            val injection = koinApplication { modules(module {
                single { queries }
                single { conversations }
            }) }.also(contexts::add)
            compose.setContent {
                KoinIsolatedContext(injection) {
                    MaterialTheme {
                        val toaster = rememberToasterState()
                        CompositionLocalProvider(
                            LocalSettings provides Settings(),
                            LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                            LocalNavController provides navigator,
                            LocalToaster provides toaster,
                        ) {
                            Box {
                                ConversationUnavailable(
                                    title = text(R.string.chat_conversation_load_failed_title),
                                    message = text(R.string.chat_conversation_load_failed_message),
                                    diagnostic = "conversation_assistant_unavailable",
                                    assistantSelection = selection,
                                )
                                Toaster(state = toaster)
                            }
                        }
                    }
                }
            }
        }
    }
}
