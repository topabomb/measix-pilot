package net.weero.measix.pilot.ui.pages.imggen

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.paging.PagingData
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.imggen.AssistantBackgroundService
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.utils.getActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class GallerySelectionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun partialSaveKeepsOnlyFailedIdsSelectedForRetry() {
        val fixture = Fixture()
        val attempts = mutableListOf<Set<Int>>()
        coEvery { fixture.vm.saveGallerySelection(any(), any(), any(), any(), any()) } coAnswers {
            val ids = secondArg<Set<Int>>()
            attempts += ids
            org.junit.Assert.assertSame(compose.activity, thirdArg<android.content.Context>().getActivity())
            val publish = arg<(GeneratedImageBatchItemResult) -> Unit>(4)
            ids.forEach { id ->
                val key = ManagedFileKey.Generated(id, fixture.selection.value)
                publish(if (id == 2 && attempts.size == 1) GeneratedImageBatchItemResult.Failed(key, java.io.IOException("gallery save fixture"))
                    else GeneratedImageBatchItemResult.Saved(key, "image-$id.png"))
            }
        }
        try {
            show(fixture)
            selectLoaded()
            button(R.string.imggen_page_save).performScrollTo().performClick()
            compose.waitUntil { attempts.size == 1 }
            closeResults()
            compose.onNodeWithText(compose.activity.getString(R.string.gallery_batch_selected, 1)).assertExists()
            button(R.string.imggen_page_save).performScrollTo().performClick()
            compose.waitUntil { attempts.size == 2 }
            compose.runOnIdle { assertEquals(listOf(setOf(1, 2), setOf(2)), attempts) }
        } finally { fixture.koin.close() }
    }

    @Test fun leavingGalleryCancelsTheBatchWithoutRepeatingAlreadyCompletedItems() {
        val fixture = Fixture()
        var active by mutableStateOf(true)
        var started = false
        var cancelled = false
        coEvery { fixture.vm.saveGallerySelection(any(), any(), any(), any(), any()) } coAnswers {
            arg<(GeneratedImageBatchItemResult) -> Unit>(4)(GeneratedImageBatchItemResult.Saved(
                ManagedFileKey.Generated(1, fixture.selection.value), "first.png"))
            started = true
            try { awaitCancellation() } finally { cancelled = true }
        }
        try {
            show(fixture) { active }
            selectLoaded()
            button(R.string.imggen_page_save).performScrollTo().performClick()
            compose.waitUntil { started }
            compose.runOnIdle { active = false }
            compose.waitUntil { cancelled }
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.runOnIdle { active = true }
            compose.onNodeWithText(compose.activity.getString(R.string.gallery_batch_selected, 1)).assertExists()
        } finally { fixture.koin.close() }
    }

    @Test fun batchDeletionRequiresConfirmationAndUsesCapturedSelectedIds() {
        val fixture = Fixture()
        var requested: Set<Int>? = null
        coEvery { fixture.vm.deleteGallerySelection(any(), any(), any()) } coAnswers {
            requested = secondArg()
            val publish = thirdArg<(GeneratedImageBatchItemResult) -> Unit>()
            requested!!.forEach { publish(GeneratedImageBatchItemResult.Deleted(ManagedFileKey.Generated(it, fixture.selection.value))) }
        }
        try {
            show(fixture)
            selectLoaded()
            button(R.string.imggen_page_delete).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(null, requested) }
            compose.onNode(hasText(compose.activity.getString(R.string.imggen_page_delete)) and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil { requested != null }
            compose.runOnIdle { assertEquals(setOf(1, 2), requested) }
        } finally { fixture.koin.close() }
    }

    @Test fun singleDeleteRetainsTheOriginalFailureThroughTheExistingBatchResult() {
        val fixture = Fixture()
        var requested: Set<Int>? = null
        coEvery { fixture.vm.deleteGallerySelection(any(), any(), any()) } coAnswers {
            requested = secondArg()
            thirdArg<(GeneratedImageBatchItemResult) -> Unit>()(GeneratedImageBatchItemResult.Failed(
                ManagedFileKey.Generated(1, fixture.selection.value), java.io.IOException("original single image failure")))
        }
        try {
            show(fixture)
            compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.imggen_page_delete))[0].performClick()
            compose.onNode(hasText(compose.activity.getString(R.string.imggen_page_delete)) and hasAnyAncestor(isDialog())).performClick()
            button(R.string.chat_conversation_diagnostics).assertIsDisplayed().performClick()
            compose.onNode(hasText("IOException: original single image failure") and
                hasAnyAncestor(hasAnyDescendant(hasContentDescription(compose.activity.getString(R.string.update_card_close)))))
                .assertIsDisplayed()
            compose.runOnIdle { assertEquals(setOf(1), requested) }
        } finally { fixture.koin.close() }
    }

    private fun button(resource: Int) = compose.onNodeWithText(compose.activity.getString(resource))
    private fun selectLoaded() {
        button(R.string.gallery_batch_select).performClick()
        button(R.string.gallery_batch_select_loaded).performScrollTo().performClick()
    }
    private fun closeResults() {
        compose.onNode(hasText(compose.activity.getString(R.string.gallery_batch_done)) and hasAnyAncestor(isDialog())).performClick()
    }
    private fun show(fixture: Fixture, active: () -> Boolean = { true }) {
        compose.setContent { KoinIsolatedContext(fixture.koin) {
            CompositionLocalProvider(LocalToaster provides rememberToasterState(),
                net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo()) {
                MaterialTheme { ImageGalleryScreen(fixture.vm, active()) }
            }
        } }
        compose.waitUntil { compose.onAllNodes(hasText(compose.activity.getString(R.string.gallery_batch_select)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }

    private class Fixture {
        val selection = MutableStateFlow(RealmSelection(RealmAccess.Personal, 1))
        val vm = mockk<ImgGenVM>()
        private val queries = mockk<ConfigurationQueryService>()
        val koin = koinApplication { modules(module {
            single { queries }
            single { mockk<AssistantBackgroundService>() }
            single { mockk<MediaExportService>() }
        }) }
        init {
            every { queries.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Loading)
            every { vm.realmSelection } returns selection
            every { vm.settings } returns MutableStateFlow(Settings.dummy())
            every { vm.generatedImages } returns flowOf(PagingData.from((1..2).map { id ->
                GeneratedImage(selection.value, id, "image prompt $id", "unused.png",
                    ImageSource("fixture-$id", ImageOrigin.INLINE, verifyAccess = {}, readPayload = { byteArrayOf() }), 0, "model")
            }))
            coEvery { vm.reconcileGallerySelection(any(), any()) } coAnswers { secondArg() }
        }
    }
}
