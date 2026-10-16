package net.weero.measix.pilot.ui.components.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.pages.chat.ConversationList
import net.weero.measix.pilot.ui.pages.chat.ConversationListItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class DirectionalIconsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backButtonKeepsNavigationAndAccessibilityActionInBothDirections() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val stack = mutableStateListOf<NavKey>(Screen.Startup(), Screen.Startup())
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value, LocalNavController provides Navigator(stack)) {
                    BackButton()
                }
            }
        }
        val label = compose.activity.getString(R.string.back)
        compose.runOnIdle { direction.value = LayoutDirection.Rtl }
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle { assertEquals(1, stack.size); stack.add(Screen.Startup()); direction.value = LayoutDirection.Ltr }
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle { assertEquals(1, stack.size) }
    }

    @Test
    fun moveToAssistantRetainsClickedConversationInBothDirections() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val conversation = ConversationSummary(Uuid.random(), ConfigurationReference.random(), "Direction test", null, false,
            Instant.EPOCH, Instant.EPOCH, null)
        val pages = flowOf(PagingData.from<ConversationListItem>(listOf(ConversationListItem.Item(conversation))))
        val moved = mutableListOf<ConversationSummary>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    val rows = pages.collectAsLazyPagingItems()
                    Column(Modifier.width(280.dp).height(300.dp)) {
                        ConversationList(conversation.id, rows, emptyMap(), rememberLazyListState(),
                            onMoveToAssistant = { moved += it })
                    }
                }
            }
        }
        val label = compose.activity.getString(R.string.chat_page_move_to_assistant)
        fun openMenu() {
            compose.onNodeWithText(conversation.title).performTouchInput { longClick() }
            compose.onNodeWithText(label).assertHasClickAction()
        }
        openMenu()
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(listOf(conversation), moved); direction.value = LayoutDirection.Rtl }
        openMenu()
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle { assertEquals(listOf(conversation, conversation), moved) }
    }
}
