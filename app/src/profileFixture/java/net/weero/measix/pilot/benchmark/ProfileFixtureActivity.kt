package net.weero.measix.pilot.benchmark

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationOpenRequest
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationReadState
import net.weero.measix.pilot.service.InitialConversationRequest
import org.koin.android.ext.android.inject

/** Dedicated profile/benchmark targets only; never packaged in formal Debug or Release. */
class ProfileFixtureActivity : ComponentActivity() {
    private val recovery by inject<ApplicationRecoveryGate>()
    private val settings by inject<SettingsStore>()
    private val conversations by inject<ConversationApplicationService>()
    private val queries by inject<ConversationQueryService>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this)
        output.id = android.R.id.text1
        setContentView(output)
        lifecycleScope.launch {
            var stage = "recovery"
            try {
                withTimeout(60_000) {
                    check(intent.getBooleanExtra("dedicatedProfileDevice", false))
                    recovery.awaitReady()
                    stage = "capture personal access"
                    check(queries.captureCurrentAccess() == RealmAccess.Personal)
                    when (intent.getStringExtra("operation")) {
                        "prepare" -> {
                            val port = intent.getIntExtra("port", 0)
                            require(port in 1024..65535)
                            val model = Model(id = MODEL, modelId = "pilot-profile", displayName = "Profile")
                            val provider = ProviderSetting.OpenAI(id = PROVIDER, name = "Profile loopback",
                                baseUrl = "http://127.0.0.1:$port/v1", models = listOf(model))
                            val assistant = Assistant(id = ASSISTANT, name = "Profile", chatModelId = MODEL,
                                enableMemory = false, localTools = emptyList(), systemPrompt = "Reply to the profile request.")
                            stage = "update fixture settings"
                            settings.updateLocal { old -> old.copy(
                                providers = old.providers.filterNot { it.id == PROVIDER } + provider,
                                assistants = old.assistants.filterNot { it.id == ASSISTANT } + assistant,
                                assistantId = ASSISTANT, chatModelId = MODEL, titleModelId = MODEL,
                                suggestionModelId = null, enableSuggestion = false,
                            ) }
                            stage = "reset fixture selection"
                            settings.rememberConversation(ConfigurationScope.Personal, null)
                            output.text = "profile:prepared"
                            android.util.Log.i("ProfileFixture", "Prepared ${intent.getStringExtra("marker")}")
                        }
                        "verify" -> {
                            val marker = requireNotNull(intent.getStringExtra("marker"))
                            require(marker.matches(Regex("PROFILE_[a-f0-9]+")))
                            stage = "await durable chat result"
                            val initial = conversations.initialRequest(createNew = false) as InitialConversationRequest.Open
                            check(initial.request is ConversationOpenRequest.OpenExisting)
                            conversations.initialize(initial.request).use { lease ->
                                queries.observeConversation(lease).first { state ->
                                    if (state is ConversationReadState.Failed) throw state.error
                                    if (state !is ConversationReadState.Ready || state.snapshot.stream != null) return@first false
                                    val messages = state.snapshot.currentMessages()
                                    val user = messages.lastOrNull { it.role == MessageRole.USER }
                                    val reply = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                                    user?.parts?.filterIsInstance<UIMessagePart.Text>()?.any { it.text == marker } == true &&
                                        reply?.parts?.filterIsInstance<UIMessagePart.Text>()?.any { marker in it.text } == true &&
                                        reply.parts.filterIsInstance<UIMessagePart.Step>().lastOrNull()?.let {
                                            it.outcome == StepOutcome.Final && it.finishedAt != null
                                        } == true
                                }
                            }
                            output.text = "profile:committed:$marker"
                            android.util.Log.i("ProfileFixture", "Committed $marker")
                        }
                        else -> error("Unknown profile fixture operation")
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                android.util.Log.e("ProfileFixture", "Timed out at $stage", timeout)
                output.text = "profile:failed at $stage\n${timeout.stackTraceToString()}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("ProfileFixture", "Failed at $stage", error)
                output.text = "profile:failed at $stage\n${error.stackTraceToString()}"
            }
        }
    }

    private companion object {
        val PROVIDER = ConfigurationReference.parse("a6a35f5e-79e8-4a63-bf73-318123ed7471")
        val MODEL = ConfigurationReference.parse("9a1d1f58-e7d6-4ac2-86e8-85f6a89ed750")
        val ASSISTANT = ConfigurationReference.parse("6b384cb2-a342-44aa-adca-68a4ee760f6e")
    }
}
