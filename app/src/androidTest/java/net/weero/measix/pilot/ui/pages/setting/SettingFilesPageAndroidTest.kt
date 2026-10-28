package net.weero.measix.pilot.ui.pages.setting

import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.imggen.AssistantBackgroundService
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import net.weero.measix.pilot.ui.context.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SettingFilesPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val commands = mockk<FileManagementApplicationService>()
    private val queries = mockk<FileManagementQueryService>()
    private val selection = RealmSelection(RealmAccess.Personal, 1)
    private val fileKey = ManagedFileKey.Artifact(1, selection)

    @Test fun failedDeleteImpactReadBlocksDeletionAndCanRetryTheSameTarget() {
        var fail = true
        coEvery { queries.inspectArtifact(fileKey) } coAnswers {
            if (fail) throw IOException("original delete inspection failure")
            ArtifactDeleteImpactUiModel(true, 0, 0, 0)
        }
        coEvery { commands.deleteArtifact(fileKey) } returns ArtifactDeleteOutcome.Deleted
        showFiles()
        compose.onNodeWithContentDescription(text(R.string.setting_files_page_delete_content_description)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_delete_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.chat_conversation_diagnostics)).assertIsDisplayed()
        coVerify(exactly = 0) { commands.deleteArtifact(any()) }
        compose.runOnIdle { fail = false }
        compose.onNodeWithText(text(R.string.application_recovery_retry)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_delete_impact_history)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_files_page_delete_action)).performClick()
        coVerify(exactly = 1) { commands.deleteArtifact(fileKey) }
    }

    @Test fun failedCleanupCountDoesNotOfferZeroAsAConfirmedDeletionPreview() {
        var fail = true
        coEvery { queries.candidateCount(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All) } coAnswers {
            if (fail) throw IOException("original cleanup count failure")
            1
        }
        coEvery { commands.cleanup(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All) } returns FileCleanupResult(1, 0, 0, 0)
        showFiles()
        compose.onNodeWithContentDescription(text(R.string.setting_files_page_clean_content_description)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_clean_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.chat_conversation_diagnostics)).assertIsDisplayed()
        coVerify(exactly = 0) { commands.cleanup(any(), any(), any()) }
        compose.runOnIdle { fail = false }
        compose.onNodeWithText(text(R.string.application_recovery_retry)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_clean_confirmation, 1)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_files_page_clean_action)).performClick()
        coVerify(exactly = 1) { commands.cleanup(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All) }
    }

    @Test fun failedDeletionRetainsItsReasonAndDiagnosticForRetry() {
        coEvery { queries.inspectArtifact(fileKey) } returns ArtifactDeleteImpactUiModel(false, 0, 0, 0)
        coEvery { commands.deleteArtifact(fileKey) } returns ArtifactDeleteOutcome.Failed(
            "payload_delete_failed", "IOException: original delete failure with cause",
        )
        showFiles()
        compose.onNodeWithContentDescription(text(R.string.setting_files_page_delete_content_description)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_delete_action)).performClick()
        compose.onNodeWithText(text(R.string.chat_conversation_diagnostics)).performClick()
        compose.onNode(
            hasText("payload_delete_failed\nIOException: original delete failure with cause") and
                hasAnyAncestor(hasAnyDescendant(hasContentDescription(text(R.string.update_card_close)))),
        ).assertIsDisplayed()
        coVerify(exactly = 1) { commands.deleteArtifact(fileKey) }
    }

    @Test fun partialCleanupKeepsDiagnosticAndRetriesTheOriginalSelection() {
        coEvery { queries.candidateCount(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All) } returns 2
        coEvery { commands.cleanup(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All) } returnsMany listOf(
            FileCleanupResult(1, 0, 0, 1, "IOException: original partial cleanup failure"),
            FileCleanupResult(1, 0, 0, 0),
        )
        showFiles()
        compose.onNodeWithContentDescription(text(R.string.setting_files_page_clean_content_description)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_clean_action)).performClick()
        compose.onNodeWithText(text(R.string.chat_conversation_diagnostics)).performClick()
        compose.onNode(
            hasText("IOException: original partial cleanup failure", substring = true) and
                hasAnyAncestor(hasAnyDescendant(hasContentDescription(text(R.string.update_card_close)))),
        ).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.update_card_close)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_clean_action)).assertIsEnabled().performClick()
        coVerify(exactly = 2) { commands.cleanup(selection, FileCleanupCategory.UPLOAD, FileCleanupRange.All) }
        compose.onNodeWithText(text(R.string.setting_files_page_clean_action)).assertDoesNotExist()
    }

    @Test fun generatedCleanupPendingRetainsDiagnosticAfterTheDirectoryRemovesItsRow() {
        val generatedKey = ManagedFileKey.Generated(2, selection)
        val directory = MutableStateFlow(FileDirectoryUiModel(selection, generated = listOf(
            ManagedFileUiModel(generatedKey, "fixture", "generated.png", "image/png", 4, null, 0, "original image", "model"),
        )))
        coEvery { commands.deleteGeneratedBatch(listOf(generatedKey), any()) } coAnswers {
            directory.value = FileDirectoryUiModel(selection)
            listOf(GeneratedImageBatchItemResult.CleanupPending(generatedKey, IOException("retained generated cleanup failure")))
        }
        showFiles(directory)
        compose.onNodeWithText(text(R.string.setting_files_page_folder_generated_images)).performClick()
        compose.onNodeWithContentDescription(text(R.string.setting_files_page_delete_content_description)).performClick()
        compose.onNodeWithText(text(R.string.setting_files_page_delete_action)).performClick()
        compose.onNodeWithText(text(R.string.chat_conversation_diagnostics)).performClick()
        compose.onNode(
            hasText("IOException: retained generated cleanup failure") and
                hasAnyAncestor(hasAnyDescendant(hasContentDescription(text(R.string.update_card_close)))),
        ).assertIsDisplayed()
        coVerify(exactly = 1) { commands.deleteGeneratedBatch(listOf(generatedKey), any()) }
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun showFiles(directory: Flow<FileDirectoryUiModel>? = null) {
        val configuration = mockk<ConfigurationQueryService>()
        every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Loading)
        every { queries.observeDirectory() } returns (directory ?: flowOf(FileDirectoryUiModel(selection, uploads = listOf(
            ManagedFileUiModel(fileKey, "attachment:1", "retained.txt", "text/plain", 4, ArtifactUiOrigin.USER, 0, null, null),
        ))))
        every { commands.imageSource(any(), any()) } returns ImageSource("fixture", ImageOrigin.UPLOAD,
            verifyAccess = {}, readPayload = { byteArrayOf() })
        compose.setContent {
            KoinApplication(application = { modules(module {
                single { commands }
                single { configuration }
                single { mockk<AssistantBackgroundService>() }
            }) }) {
                MaterialTheme {
                    CompositionLocalProvider(LocalSettings provides Settings.dummy(),
                        LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                        LocalToaster provides rememberToasterState(),
                        LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Startup()))) {
                        SettingFilesPage(commands, queries)
                    }
                }
            }
        }
    }
}
