package net.weero.measix.pilot.ui.pages.chat

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.DEFAULT_PROVIDERS
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.hooks.readBooleanPreference
import net.weero.measix.pilot.ui.hooks.writeBooleanPreference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.uuid.Uuid

/** Runs only on a dedicated Debug device with no enterprise binding or user provider configuration. */
@RunWith(AndroidJUnit4::class)
class EnterpriseEmptyAccountNavigationAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun emptyAccountCanReturnFromEnrollmentAndCreateInsideAndOutsideChatWithoutDefaultAssistant() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Use a dedicated unbound Debug AVD and enterpriseEmptyAccountLive=true",
            arguments.getString("enterpriseEmptyAccountLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".debug")) { "empty_account_requires_debug_application" }
        val koin = GlobalContext.get()
        val sessions = koin.get<EnterpriseSessionController>()
        val enterprise = koin.get<EnterpriseApplicationService>()
        val queries = koin.get<ConfigurationQueryService>()
        val conversations = koin.get<ConversationApplicationService>()
        val query = koin.get<ConversationQueryService>()
        val repository = koin.get<ConversationRepository>()
        val settings = koin.get<SettingsStore>()
        koin.get<ApplicationRecoveryCoordinator>()
        runBlocking { withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() } }
        val initial = (sessions.state.value as EnterpriseState.Available).manifest
        check(initial.phase == EnterpriseSessionPhase.SIGNED_OUT && initial.session == null &&
            initial.pendingEnrollment == null && initial.applied == null && initial.lastIdentity == null) {
            "empty_account_requires_fresh_unbound_device"
        }
        val priorSettings = runBlocking { settings.userSettings.first { !it.init } }
        check(priorSettings.providers.isEmpty() || priorSettings.providers == DEFAULT_PROVIDERS) {
            "empty_account_refuses_existing_user_provider_configuration"
        }
        val priorNewChat = context.readBooleanPreference("create_new_conversation_on_start", true)
        val priorPersonalRecent = runBlocking { settings.lastConversation(ConfigurationScope.Personal) }
        val fixture = fixtureWithoutDefaultAssistant()
        val snapshot = fixture.getValue("snapshot").jsonObject
        val starter = snapshot.getValue("starters").jsonArray.single().jsonObject
        val title = starter.getValue("title").jsonPrimitive.content
        val prompt = starter.getValue("prompt").jsonPrimitive.content
        val server = StarterV5HttpMock(fixture)
        val evidence = File(arguments.getString("additionalTestOutputDir")?.let(::File)
            ?: context.getExternalFilesDir(null), "enterprise-empty-account-evidence").apply { check(isDirectory || mkdirs()) }
        var activity: ActivityScenario<RouteActivity>? = null
        var uiContext: Context = context
        var fixtureAccess: RealmAccess.Enterprise? = null
        var createdId: Uuid? = null
        var view: ConversationViewLease? = null
        var failure: Throwable? = null
        fun text(id: Int) = uiContext.getString(id)
        fun openDrawer() {
            val drawer = compose.onAllNodesWithContentDescription(text(R.string.chat_navigation_open_drawer))
            val sidebar = compose.onAllNodesWithContentDescription(text(R.string.chat_sidebar_expand))
            when {
                drawer.fetchSemanticsNodes().isNotEmpty() -> drawer[0].performClick()
                sidebar.fetchSemanticsNodes().isNotEmpty() -> sidebar[0].performClick()
            }
        }
        fun openStarter() {
            compose.onNodeWithTag("space-start-conversation").assertIsEnabled().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(text(R.string.enterprise_starter_open)).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_input") and hasText(prompt)).fetchSemanticsNodes().size == 1
            }
        }
        try {
            context.writeBooleanPreference("create_new_conversation_on_start", true)
            activity = ActivityScenario.launch(Intent(context, RouteActivity::class.java))
            activity.onActivity { uiContext = it }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().size == 1 }
            openDrawer()
            compose.onNodeWithText(text(R.string.enterprise_switch_space)).performClick()
            compose.onNodeWithTag("space-start-conversation").assertIsDisplayed()
            val material = buildJsonObject {
                put("formatVersion", 1); put("kind", "PLATFORM_ENROLLMENT")
                put("platformUrl", server.origin); put("code", "empty-account-dedicated-fixture")
                put("expiresAt", Instant.now().plusSeconds(3600).toString())
            }.toString()
            assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED, awaitUi(60_000) {
                enterprise.confirmJoin(requireNotNull(enterprise.join(material)))
            })
            val selection = awaitUi(30_000) { requireNotNull(sessions.observeSelectedRealmSelection()
                .first { it?.access is RealmAccess.Enterprise }) }
            val access = selection.access as RealmAccess.Enterprise
            fixtureAccess = access
            assertEquals(server.deploymentId, access.scope.authority.deploymentId)
            assertNull(awaitUi(30_000) { queries.read(access).selections.assistantId })
            assertEquals(1, server.enrollments.get())
            // Return as soon as the real enrollment command completes; no restart or extra selection.
            val enrollmentPage = compose.onNodeWithTag("space-start-conversation").fetchSemanticsNode().id
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("space-start-conversation").fetchSemanticsNodes().singleOrNull()?.id
                    ?.let { it != enrollmentPage } == true &&
                    compose.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("space-start-conversation").assertIsEnabled()
            openStarter()
            assertNull(awaitUi(30_000) { queries.read(access).storedSelections.assistantId })
            assertTrue(awaitUi(30_000) { query.recentConversations(access,
                me.rerere.common.configuration.ConfigurationReference.Enterprise(access.scope.authority,
                    starter.getValue("assistantDefinitionId").jsonPrimitive.content), 10) }.isEmpty())
            assertTrue(server.modelRequests.isEmpty())
            closeSoftKeyboard()
            compose.onNodeWithTag("chat_send_button").assertIsEnabled().performClick()
            val row = awaitUi(30_000) {
                query.conversationsInRealm(selection).first { it.getOrThrow().isNotEmpty() }.getOrThrow().single()
            }
            createdId = row.id
            val lease = awaitUi(30_000) { conversations.initialize(ConversationOpenRequest.OpenExisting(row.id, access)) }
            view = lease
            val completed = awaitUi(60_000) { query.conversationUiModel(lease).first { model ->
                model != null && model.presentation.activeTurnId == null &&
                    model.snapshot.currentMessages().lastOrNull()?.toText() == "V5 mock response"
            }!! }
            val saved = awaitUi(30_000) { requireNotNull(repository.getConversationSnapshotById(row.id)) }
            val answer = saved.currentMessages().last()
            assertEquals(MessageRole.ASSISTANT, answer.role)
            assertNull(answer.terminalStatus)
            assertEquals(StepOutcome.Final, answer.parts.filterIsInstance<UIMessagePart.Step>().last().outcome)
            assertEquals(completed.snapshot.currentMessages(), saved.currentMessages())
            assertEquals(prompt, saved.currentMessages().single { it.role == MessageRole.USER }.toText())
            assertEquals(access.scope, saved.header.scope)
            assertNotNull(saved.opening)
            assertTrue(server.modelRequests.isNotEmpty())
            val historyTitle = "Enterprise navigation history ${row.id}"
            awaitUi(30_000) { conversations.updateTitle(lease.commandTarget, historyTitle) }

            val previousInput = compose.onNodeWithTag("chat_input").fetchSemanticsNode().id
            compose.onNodeWithContentDescription(text(R.string.chat_page_new_chat)).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("chat_input") and hasText("")).fetchSemanticsNodes().singleOrNull()?.id
                    ?.let { it != previousInput } == true &&
                    compose.onAllNodesWithText("V5 mock response").fetchSemanticsNodes().isEmpty()
            }
            assertNull(awaitUi(30_000) { queries.read(access).storedSelections.assistantId })
            assertEquals(listOf(row.id), awaitUi(30_000) { query.recentConversations(access, saved.header.assistantId, 10).map { it.id } })
            openDrawer()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(historyTitle).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(historyTitle).assertIsDisplayed()
            val enterpriseName = awaitUi(30_000) { requireNotNull(queries.read(access).enterpriseIdentity).enterpriseName }
            compose.onNodeWithText(enterpriseName).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("space-start-conversation").fetchSemanticsNodes().size == 1 }
            openStarter()
            assertNull(awaitUi(30_000) { queries.read(access).storedSelections.assistantId })
            assertEquals(listOf(row.id), awaitUi(30_000) { query.recentConversations(access, saved.header.assistantId, 10).map { it.id } })
            assertEquals(priorSettings.providers, runBlocking { settings.userSettings.first { !it.init }.providers })
            assertEquals(priorPersonalRecent, awaitUi(30_000) { settings.lastConversation(ConfigurationScope.Personal) })
        } catch (error: Throwable) {
            failure = error
            try {
                capture(evidence, "failure.png")
                File(evidence, "failure-semantics.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true)
                    .fetchSemanticsNodes().indices.joinToString("\n\n") {
                        compose.onAllNodes(isRoot(), useUnmergedTree = true)[it].printToString()
                    })
            } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        } finally {
            val primary = failure
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            cleanup { activity?.close() }
            cleanup { view?.close() }
            cleanup { runBlocking { withTimeout(60_000) {
                val current = (sessions.state.value as EnterpriseState.Available).manifest.session
                if (current != null) {
                    val access = RealmAccess.Enterprise(current.identity.scope, current.id)
                    check(current.identity.authority.deploymentId == server.deploymentId &&
                        current.id == fixture.getValue("enrollment-response").jsonObject.getValue("sessionId").jsonPrimitive.content &&
                        (fixtureAccess == null || fixtureAccess == access)) { "refuse_foreign_session_cleanup" }
                    val selected = requireNotNull(sessions.readPresentation().selection)
                    if (selected.access != access) enterprise.switchRealm(RealmSwitchRequest(selected, access))
                    createdId?.let { id ->
                        val page = conversations.initialize(ConversationOpenRequest.OpenExisting(id, access))
                        try { conversations.delete(page.commandTarget) } finally { page.close() }
                    }
                    val result = enterprise.exit(requireNotNull(enterprise.captureExitRequest()))
                    check(result.maintenanceFailure == null && result.remoteLogoutFailure == null) { "empty_account_exit_cleanup_failed" }
                }
            } } }
            cleanup { context.writeBooleanPreference("create_new_conversation_on_start", priorNewChat) }
            cleanup { File(evidence, "request-diagnostics.txt").writeText(server.diagnostics()) }
            cleanup { server.close() }
            if (primary == null) failure?.let { throw it }
        }
    }

    private fun fixtureWithoutDefaultAssistant(): JsonObject {
        val source = InstrumentationRegistry.getInstrumentation().context.assets
            .open("contracts/starter-v5-e2e.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val snapshot = source.getValue("snapshot").jsonObject
        val body = JsonObject(snapshot + ("policy" to JsonObject(snapshot.getValue("policy").jsonObject - "defaultAssistantId")) - "snapshotHash")
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return JsonObject(source + ("snapshot" to JsonObject(body + ("snapshotHash" to JsonPrimitive("sha256:$hash")))))
    }

    private fun <T> awaitUi(timeout: Long, action: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val pending = scope.async { withTimeout(timeout) { action() } }
        return try { compose.waitUntil(timeout + 5_000) { pending.isCompleted }; runBlocking { pending.await() } }
        finally { scope.cancel() }
    }

    private fun capture(directory: File, name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
