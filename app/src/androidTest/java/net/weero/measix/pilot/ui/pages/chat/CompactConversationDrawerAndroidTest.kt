package net.weero.measix.pilot.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.service.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class CompactConversationDrawerAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun rows(count: Int) = List(count) { index ->
        ConversationListItem.Item(ConversationSummary(Uuid.random(), ConfigurationReference.random(),
            "Conversation $index", null, false, Instant.EPOCH, Instant.EPOCH, null))
    }

    @Test
    fun shortViewportScrollsHeaderAndFooterAndRestoredHeightKeepsTheVisibleConversation() {
        val items = rows(40)
        val pages = flowOf(PagingData.from<ConversationListItem>(items))
        val height = mutableStateOf(150.dp)
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                val data = pages.collectAsLazyPagingItems()
                state = rememberLazyListState()
                Box(Modifier.width(300.dp).height(height.value)) {
                    ConversationList(items[15].conversation.id, data, emptyMap(), state,
                        header = { Column { repeat(7) { Text("Space header $it") } } },
                        footer = { Column { Text("Assistant selector"); Text("Settings action") } })
                }
            }
        }
        compose.onNodeWithText("Conversation 15").assertIsDisplayed()
        compose.onNodeWithText("Space header 0").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(41)
        compose.onNodeWithText("Assistant selector").assertIsDisplayed()
        compose.onNodeWithText("Settings action").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(16)
        compose.onNodeWithText("Conversation 15").assertIsDisplayed()
        compose.runOnIdle { height.value = 600.dp }
        compose.onNodeWithText("Space header 0").assertIsDisplayed()
        compose.onNodeWithText("Assistant selector").assertIsDisplayed()
        compose.onNodeWithText("Conversation 15").assertIsDisplayed()
        compose.runOnIdle {
            val current = state.layoutInfo.visibleItemsInfo.first { it.key == items[15].conversation.id.toString() }
            assertTrue("The preserved conversation must remain inside the list viewport", current.offset >= 0)
        }
    }

    @Test
    fun largeFontChangesTheFitDecisionWithoutChangingWindowHeight() {
        val items = rows(40)
        val pages = flowOf(PagingData.from<ConversationListItem>(items))
        val fontScale = mutableStateOf(1f)
        var headerHeight = 0
        var footerHeight = 0
        var viewportHeight = 0
        var minimumListHeight = 0
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.value)) {
                MaterialTheme {
                    val data = pages.collectAsLazyPagingItems()
                    val density = LocalDensity.current
                    val lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                    minimumListHeight = with(density) {
                        2 * maxOf(48.dp.roundToPx(), (lineHeight.toPx() + 10.dp.toPx()).toInt())
                    }
                    Box(Modifier.width(300.dp).height(400.dp).onSizeChanged { viewportHeight = it.height }) {
                        ConversationList(items[15].conversation.id, data, emptyMap(), rememberLazyListState(),
                            header = {
                                Column(Modifier.onSizeChanged { headerHeight = it.height }) {
                                    repeat(7) { Text("Space header $it", style = MaterialTheme.typography.bodyLarge) }
                                }
                            },
                            footer = {
                                Column(Modifier.onSizeChanged { footerHeight = it.height }) {
                                    Text("Assistant selector", style = MaterialTheme.typography.bodyLarge)
                                    Text("Settings action", style = MaterialTheme.typography.bodyLarge)
                                }
                            })
                    }
                }
            }
        }
        compose.onNodeWithText("Space header 0").assertIsDisplayed()
        compose.onNodeWithText("Conversation 15").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Normal text must leave room for two conversation rows: header=$headerHeight footer=$footerHeight viewport=$viewportHeight minimum=$minimumListHeight",
                headerHeight + footerHeight + minimumListHeight <= viewportHeight)
            fontScale.value = 2f
        }
        compose.onNodeWithText("Conversation 15").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("Large text must exhaust the fixed-header budget: header=$headerHeight footer=$footerHeight viewport=$viewportHeight minimum=$minimumListHeight",
                headerHeight + footerHeight + minimumListHeight > viewportHeight)
        }
        compose.onNodeWithText("Space header 0").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(41)
        compose.onNodeWithText("Settings action").assertIsDisplayed()
    }

    @Test
    fun locatingCurrentConversationIncludesLeadingPagingPlaceholdersAndHeader() {
        val items = rows(50)
        val pages = Pager(PagingConfig(pageSize = 50, initialLoadSize = 50, enablePlaceholders = true)) {
            object : PagingSource<Int, ConversationListItem>() {
                override suspend fun load(params: LoadParams<Int>) = LoadResult.Page<Int, ConversationListItem>(
                    data = items, prevKey = null, nextKey = null, itemsBefore = 7, itemsAfter = 0)
                override fun getRefreshKey(state: PagingState<Int, ConversationListItem>): Int? = null
            }
        }.flow
        lateinit var state: LazyListState
        var before = -1
        compose.setContent {
            MaterialTheme {
                val data = pages.collectAsLazyPagingItems()
                before = data.itemSnapshotList.placeholdersBefore
                state = rememberLazyListState()
                Box(Modifier.width(300.dp).height(90.dp)) {
                    ConversationList(items[30].conversation.id, data, emptyMap(), state,
                        header = { Text("Space header") }, footer = { Text("Assistant selector") })
                }
            }
        }
        compose.onNodeWithText("Conversation 30").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(7, before)
            assertEquals(38, state.firstVisibleItemIndex)
            assertEquals(items[30].conversation.id.toString(), state.layoutInfo.visibleItemsInfo.first().key)
        }
    }
}
