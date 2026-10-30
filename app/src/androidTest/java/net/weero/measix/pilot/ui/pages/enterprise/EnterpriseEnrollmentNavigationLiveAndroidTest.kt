package net.weero.measix.pilot.ui.pages.enterprise

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.datastore.DEFAULT_PROVIDERS
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ApplicationRecoveryCoordinator
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** Real enrollment on a dedicated fresh Debug device; retains its Session and never sends a model request. */
@RunWith(AndroidJUnit4::class)
class EnterpriseEnrollmentNavigationLiveAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun freshEnrollmentReturnsToChatAndSpaceSwitchReturnsThroughItsSettingsCaller() {
        val arguments = InstrumentationRegistry.getArguments()
        val inputName = arguments.getString("enterpriseNavigationInput")
        assumeTrue("Requires a fresh dedicated Debug AVD and a one-use private cache enrollment file",
            !inputName.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".debug")) { "enterprise_navigation_requires_debug_application" }
        val cache = context.cacheDir.canonicalFile
        val inputPath = requireNotNull(inputName)
        val input = File(inputPath).let { if (it.isAbsolute) it else File(cache, inputPath) }.canonicalFile
        check(input.toPath().startsWith(cache.toPath()) && input != cache && input.isFile && input.length() in 1L..65_536L) {
            "enterprise_navigation_requires_private_cache_input"
        }
        val koin = GlobalContext.get()
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
        val sessions = koin.get<EnterpriseSessionController>()
        val queries = koin.get<ConfigurationQueryService>()
        val conversations = koin.get<ConversationQueryService>()
        val settings = koin.get<SettingsStore>()
        val runtimes = koin.get<ConversationRuntimeRegistry>()
        val initial = (sessions.state.value as EnterpriseState.Available).manifest
        check(initial.phase == EnterpriseSessionPhase.SIGNED_OUT && initial.session == null &&
            initial.pendingEnrollment == null && initial.applied == null && initial.lastIdentity == null) {
            "enterprise_navigation_requires_fresh_unbound_device"
        }
        val initialSettings = runBlocking { settings.userSettings.first { !it.init } }
        check(initialSettings.providers.isEmpty() || initialSettings.providers == DEFAULT_PROVIDERS) {
            "enterprise_navigation_refuses_existing_provider_configuration"
        }
        val personalSelection = runBlocking { requireNotNull(sessions.readPresentation().selection) }
        check(personalSelection.access == RealmAccess.Personal) { "enterprise_navigation_requires_personal_start" }
        check(runBlocking { conversations.conversationsInRealm(personalSelection).first().getOrThrow().isEmpty() }) {
            "enterprise_navigation_refuses_existing_personal_conversations"
        }

        val evidence = File(context.getExternalFilesDir(null), "enterprise-enrollment-navigation-live").apply {
            check(isDirectory || mkdirs())
        }
        val completed = mutableListOf<String>()
        var stage = "launch"
        var credentialsRemovedFromUi = false
        var inputDeleted = false
        var defaultAssistantAvailable = false
        var failureType: String? = null
        var activity: ActivityScenario<RouteActivity>? = null
        var uiContext: Context = context
        fun text(id: Int) = uiContext.getString(id)
        fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        fun waitForChatEntry() {
            compose.waitUntil(30_000) { hasTag("chat_input") }
            compose.onNodeWithTag("enterprise-page-menu").assertDoesNotExist()
            compose.onNodeWithTag("space-start-conversation").assertDoesNotExist()
        }
        fun openDrawer() {
            closeSoftKeyboard()
            val drawer = compose.onAllNodesWithContentDescription(text(R.string.chat_navigation_open_drawer))
            val sidebar = compose.onAllNodesWithContentDescription(text(R.string.chat_sidebar_expand))
            when {
                drawer.fetchSemanticsNodes().isNotEmpty() -> drawer[0].performClick()
                sidebar.fetchSemanticsNodes().isNotEmpty() -> sidebar[0].performClick()
            }
        }
        fun openSettings() {
            openDrawer()
            compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(text(R.string.setting_page_general_settings)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun openSpaceFromSettings() {
            compose.onNodeWithText(text(R.string.enterprise_spaces_desc)).performScrollTo().performClick()
            compose.onNodeWithTag("enterprise-page-menu").assertIsDisplayed()
        }
        fun assertSettingsReturned() {
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(text(R.string.setting_page_general_settings)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("enterprise-page-menu").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.setting_page_general_settings)).assertIsDisplayed()
        }
        fun currentSelection() = awaitUi(30_000) { requireNotNull(sessions.readPresentation().selection) }
        fun assertNoSavedConversations(selection: RealmSelection) {
            assertTrue("Unsent navigation must not persist a conversation",
                awaitUi(30_000) { conversations.conversationsInRealm(selection).first().getOrThrow().isEmpty() })
        }

        try {
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { hasTag("chat_input") }
            openDrawer()
            compose.onNodeWithText(text(R.string.enterprise_switch_space)).performClick()
            compose.onNodeWithTag("space-start-conversation").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.enterprise_join_paste)).performClick()
            stage = "enrollment_input"
            // Input material is deliberately absent from instrumentation arguments, evidence and thrown diagnostics.
            var material: String? = try {
                input.readText(Charsets.UTF_8)
            } finally {
                inputDeleted = input.delete()
                check(inputDeleted) { "enterprise_navigation_input_delete_failed" }
            }
            try {
                compose.onNode(hasSetTextAction()).performTextInput(requireNotNull(material))
                compose.onNodeWithText(text(R.string.enterprise_join_submit)).performClick()
            } catch (_: Throwable) {
                throw AssertionError("enterprise_navigation_credential_input_failed")
            } finally {
                material = null
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(text(R.string.confirm)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
            }
            credentialsRemovedFromUi = true
            stage = "enrollment_confirmation"
            compose.onNodeWithText(text(R.string.confirm)).performClick()
            val joined = awaitUi(90_000) {
                sessions.state.first { value ->
                    value is EnterpriseState.Available && value.manifest.phase == EnterpriseSessionPhase.READY &&
                        value.configuration != null
                }
                sessions.observeSelectedRealmSelection().first { it?.access is RealmAccess.Enterprise }!!
            }
            val access = joined.access as RealmAccess.Enterprise
            val configuration = awaitUi(30_000) { queries.read(access) }
            val enterpriseName = requireNotNull(configuration.enterpriseIdentity).enterpriseName
            defaultAssistantAvailable = configuration.selections.assistantId != null &&
                configuration.selection(net.weero.measix.pilot.data.configuration.ConfigurationCategory.ASSISTANT,
                    requireNotNull(configuration.selections.assistantId)).isAvailable
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(text(R.string.enterprise_switch_personal)).fetchSemanticsNodes().isNotEmpty()
            }
            capture(evidence, "01-enrolled-spaces.png")
            completed += "real_ui_enrollment_ready"

            stage = "toolbar_back_after_enrollment"
            compose.onNodeWithContentDescription(text(R.string.back)).assertIsEnabled().performClick()
            waitForChatEntry()
            capture(evidence, "02-returned-from-enrollment.png")
            completed += "toolbar_back_leaves_spaces"
            assertNoSavedConversations(joined)
            completed += "unsent_chat_not_persisted"

            stage = "new_chat_from_unsent_enterprise_draft"
            val originalRuntime = runtimes.activeRuntimes().single {
                it.snapshot.value.durable.header.scope == access.scope
            }
            val originalAssistant = originalRuntime.snapshot.value.durable.header.assistantId
            val originalInput = compose.onNodeWithTag("chat_input").fetchSemanticsNode().id
            compose.onNodeWithTag("chat_input").performTextInput("New chat verification — not sent")
            closeSoftKeyboard()
            compose.onNodeWithContentDescription(text(R.string.chat_page_new_chat)).assertIsEnabled().performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_input") and hasText("")).fetchSemanticsNodes().singleOrNull()?.id
                    ?.let { it != originalInput } == true
            }
            compose.onNodeWithText(text(R.string.error_title_operation)).assertDoesNotExist()
            val newRuntime = runtimes.activeRuntimes().single {
                it.id != originalRuntime.id && it.snapshot.value.durable.header.scope == access.scope
            }
            assertEquals(originalAssistant, newRuntime.snapshot.value.durable.header.assistantId)
            assertTrue(runtimes.isDraft(newRuntime.id))
            assertTrue("New chat must keep the original selection", joined == currentSelection())
            assertNoSavedConversations(joined)
            capture(evidence, "03-new-chat-keeps-enterprise-assistant.png")
            completed += "new_chat_from_unsent_draft_keeps_selection_and_assistant"

            stage = "system_back_preserves_chat_draft"
            val unsent = "Navigation verification — unsent"
            compose.onNodeWithTag("chat_input").performTextInput(unsent)
            openDrawer()
            compose.onNodeWithText(enterpriseName).performClick()
            compose.onNodeWithTag("enterprise-page-menu").assertIsDisplayed()
            compose.onNodeWithTag("space-start-conversation").assertDoesNotExist()
            activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNode(hasTestTag("chat_input") and hasText(unsent)).assertIsDisplayed()
            assertNoSavedConversations(joined)
            capture(evidence, "04-system-back-preserves-draft.png")
            completed += "system_back_preserves_original_draft"

            stage = "settings_space_switch_to_personal"
            openSettings()
            openSpaceFromSettings()
            compose.onNodeWithText(text(R.string.enterprise_switch_personal)).performScrollTo().performClick()
            assertSettingsReturned()
            val personal = currentSelection()
            assertEquals(RealmAccess.Personal, personal.access)
            capture(evidence, "05-personal-switch-returned-settings.png")
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            waitForChatEntry()
            assertNoSavedConversations(personal)
            completed += "personal_switch_returns_settings_then_current_chat"

            stage = "settings_space_switch_to_enterprise"
            openSettings()
            openSpaceFromSettings()
            compose.onNodeWithText(text(R.string.enterprise_switch_enterprise)).performScrollTo().performClick()
            assertSettingsReturned()
            val enterprise = currentSelection()
            assertTrue("Space switching must keep the enrolled Session", access == enterprise.access)
            capture(evidence, "06-enterprise-switch-returned-settings.png")
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            waitForChatEntry()
            assertNoSavedConversations(enterprise)
            assertTrue("Enrollment must preserve personal providers",
                initialSettings.providers == awaitUi(30_000) { settings.userSettings.first { !it.init }.providers })
            capture(evidence, "07-current-enterprise-chat.png")
            completed += "enterprise_switch_returns_settings_then_current_chat"
            stage = "complete"
        } catch (error: Throwable) {
            failureType = error.javaClass.name
            if (credentialsRemovedFromUi) {
                try { capture(evidence, "failure.png") } catch (_: Throwable) { /* Never obscure the primary failure. */ }
            }
            throw error
        } finally {
            val diagnostic = buildJsonObject {
                put("stage", stage)
                put("passed", stage == "complete")
                put("credentialInputDeleted", inputDeleted)
                put("defaultAssistantAvailable", defaultAssistantAvailable)
                put("modelMessageSentByTest", false)
                put("sessionRetained", (sessions.state.value as? EnterpriseState.Available)?.manifest?.session != null)
                put("failureType", failureType)
                put("completed", buildJsonArray { completed.forEach { add(it) } })
                put("limits", buildJsonArray {
                    add("Uses the UI paste enrollment path; camera QR scanning is not exercised.")
                    add("No model, microphone, workspace execution or Portal request is submitted.")
                    add("Only the published account configuration on this run is covered.")
                    add("New-chat is exercised from an unsent Draft; Ready conversation coverage belongs to the deterministic integration tests.")
                })
            }
            File(evidence, "result.json").writeText(diagnostic.toString())
            activity?.close()
        }
    }

    private fun <T> awaitUi(timeout: Long, action: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val pending = scope.async { withTimeout(timeout) { action() } }
        return try {
            compose.waitUntil(timeout + 5_000) { pending.isCompleted }
            runBlocking { pending.await() }
        } finally {
            scope.cancel()
        }
    }

    private fun capture(directory: File, name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
