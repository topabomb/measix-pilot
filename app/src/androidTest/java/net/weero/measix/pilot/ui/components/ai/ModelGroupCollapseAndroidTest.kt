package net.weero.measix.pilot.ui.components.ai

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ModelCatalogUiModel
import net.weero.measix.pilot.service.ModelChoiceUiModel
import net.weero.measix.pilot.service.ModelGroupUiModel
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.Navigator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelGroupCollapseAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun delayedPreferencesLocateCollapsedSelectedGroupAndSearchDoesNotPersistExpansion() {
        val groups = listOf("Provider A", "Provider B").map { name ->
            val id = ConfigurationReference.random() as ConfigurationReference.User
            ModelGroupUiModel(id.toString(), name, (0 until 50).map { index ->
                ModelChoiceUiModel(Model(modelId = "$name-$index", displayName = "$name model $index"), null)
            }, id)
        }
        val catalog = ModelCatalogUiModel(groups)
        val state = ModelListState(groups.first().models[40].model.id, catalog, ModelType.CHAT).apply { open() }
        val preferences = MutableSharedFlow<Result<Set<String>>>(replay = 1)
        val queries = mockk<ConfigurationQueryService>()
        val commands = mockk<ConfigurationApplicationService>(relaxed = true)
        every { queries.observePersonalModelFavorites() } returns flowOf(emptyList())
        every { queries.observeCollapsedModelGroups(null) } returns preferences
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup())),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                ) {
                    ModelListSheet(state, onSelect = {}, configurationCommands = commands, configurationQueries = queries)
                }
            }
        }
        try {
            compose.waitForIdle()
            compose.runOnIdle { preferences.tryEmit(Result.success(setOf(groups.first().id))) }
            compose.onNodeWithText("Provider A").assertIsDisplayed()
            compose.onNodeWithText("Provider A model 40").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextReplacement("Provider A model 40")
            compose.onNode(hasText("Provider A model 40") and !hasSetTextAction()).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNodeWithText("Provider A").assertIsDisplayed()
            compose.onNodeWithText("Provider A model 40").assertDoesNotExist()
            coVerify(exactly = 0) { commands.setModelGroupCollapsed(any(), any(), any()) }
        } finally {
            // Close the focused native sheet before the Activity rule destroys its window.
            compose.runOnIdle { state.close() }
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5_000)
        }
    }
}
