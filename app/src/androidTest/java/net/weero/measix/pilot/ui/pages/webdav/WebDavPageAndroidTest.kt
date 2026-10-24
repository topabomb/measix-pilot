package net.weero.measix.pilot.ui.pages.webdav

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavBackupPreferences
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.webdav.DavDirectory
import net.weero.measix.pilot.data.webdav.DavFile
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.webdav.*
import net.weero.measix.pilot.ui.context.*
import net.weero.measix.pilot.ui.pages.setting.*
import net.weero.measix.pilot.ui.pages.backup.BackupVM
import net.weero.measix.pilot.ui.pages.backup.tabs.WebDavTab
import net.weero.measix.pilot.utils.UiState
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native layout and source selection; HTTP contracts have separate service tests. */
@RunWith(AndroidJUnit4::class)
class WebDavPageAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun dispose() { compose.runOnUiThread { store.clear(); scope.cancel() } }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf(Screen.Enterprise)),
                    LocalSettings provides Settings(),
                    LocalToaster provides net.weero.measix.pilot.ui.components.toast.rememberToasterState(),
                    net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo()) {
                    content()
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun enterpriseSettingsShowsReadonlyCurrentWithoutPersonalEditor() {
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(
            EnterpriseAuthority("dep_12345678-1234-4234-8234-123456789abc"), "usr_12345678-1234-4234-8234-123456789abc"), "session")
        val selection = RealmSelection(access, 1)
        val resources = listOf(WebDavResourceSummary("bound", "Enterprise files", true, false, "3"),
            WebDavResourceSummary("unbound", "Other service", false, false, "1", "webdav_binding_unavailable"))
        val state = MutableStateFlow<WebDavConfigurationReadState>(WebDavConfigurationReadState.Available(selection,
            emptyList(), null, enterpriseResources = resources, designatedId = "bound"))
        val queries = mockk<ConfigurationQueryService> {
            every { observeWebDavConfiguration() } returns state
            every { observeSelectedRealmSelection() } returns flowOf(selection)
        }
        val dav = mockk<WebDavApplicationService> { coEvery { refresh(selection) } returns Unit }
        lateinit var vm: WebDavSettingsVM
        compose.runOnUiThread { vm = WebDavSettingsVM(queries, mockk(), dav).also { store.put("settings", it) } }
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) { SettingWebDavPage(vm) }
        }
        compose.onNodeWithText("Enterprise files").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.webdav_current)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mcp_enterprise_read_only)).assertIsDisplayed()
        compose.onNodeWithText("Other service").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.webdav_add_connection)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.enterprise_refresh_status)).performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { dav.refresh(selection) }
        capture("webdav-enterprise-settings-large-font.png")
    }

    @Test fun personalBrowserSwitchesServerWithoutChangingCurrentConfiguration() {
        val selection = RealmSelection(RealmAccess.Personal, 1)
        val first = WebDavConnection(name = "Primary", rootUrl = "https://one.example/")
        val second = WebDavConnection(name = "Secondary", rootUrl = "https://two.example/")
        val state = MutableStateFlow<WebDavConfigurationReadState>(WebDavConfigurationReadState.Available(selection,
            listOf(first, second), first.id))
        val queries = mockk<ConfigurationQueryService> {
            every { observeWebDavConfiguration() } returns state
            every { observeSelectedRealmSelection() } returns flowOf(selection)
        }
        val dav = mockk<WebDavApplicationService> {
            coEvery { open(selection, any()) } answers {
                val chosen = if (secondArg<String?>() == second.id.toString()) second else first
                WebDavHandle(selection.access, selection, WebDavTarget.Personal(chosen))
            }
            coEvery { validate(any(), any()) } returns Unit
            coEvery { validateLocal(any()) } returns Unit
            coEvery { isValidLocal(any()) } returns true
            coEvery { close(any()) } answers { firstArg<WebDavHandle>().closed.set(true) }
            coEvery { refresh(selection) } returns Unit
            coEvery { list(any(), any(), any()) } answers {
                DavDirectory(listOf(DavFile("${firstArg<WebDavHandle>().target.name}.txt", false, 24, null, "\"v1\"")), null, null, null)
            }
        }
        val browser = WebDavBrowserService(dav, queries, ApplicationRecoveryGate().apply { ready() }, scope,
            File(compose.activity.cacheDir, "webdav-ui-${first.id}"))
        lateinit var vm: WebDavFileVM
        compose.runOnUiThread { vm = WebDavFileVM(selection, queries, browser, compose.activity).also { store.put("files", it) } }
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier
                    .then(androidx.compose.ui.Modifier.height(280.dp))) { WebDavFilePage(selection, vm) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.directory != null }
        compose.onNodeWithText("Primary.txt").assertIsDisplayed()
        compose.onNodeWithText("Primary", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Secondary").performClick()
        compose.waitUntil(5_000) { vm.targetId.value == second.id.toString() && vm.state.value.directory?.files?.singleOrNull()?.name == "Secondary.txt" }
        compose.onNodeWithText("Secondary.txt").assertIsDisplayed()
        assertEquals(first.id.toString(), (state.value as WebDavConfigurationReadState.Available).currentId)
        capture("webdav-personal-server-switch.png")

        // A revoked browser target must not disable choosing the remaining authorized server.
        coEvery { dav.isValidLocal(any()) } answers { firstArg<WebDavHandle>().target.id == first.id.toString() }
        state.value = WebDavConfigurationReadState.Available(selection, listOf(first), first.id)
        compose.waitUntil(5_000) { vm.state.value.revoked }
        compose.onNodeWithText(text(R.string.webdav_no_current), useUnmergedTree = true).performClick()
        compose.onNodeWithText("Primary").performClick()
        compose.waitUntil(5_000) { !vm.state.value.revoked && vm.state.value.directory?.files?.singleOrNull()?.name == "Primary.txt" }
        compose.onNodeWithText("Primary.txt").assertIsDisplayed()
        assertEquals(first.id.toString(), (state.value as WebDavConfigurationReadState.Available).currentId)
    }

    @Test fun enterpriseWithoutDavExplainsMissingProvisioningWithoutOfferingPersonalConfiguration() {
        val selection = RealmSelection(RealmAccess.Enterprise(ConfigurationScope.Enterprise(
            EnterpriseAuthority("dep_12345678-1234-4234-8234-123456789abc"), "usr_12345678-1234-4234-8234-123456789abc"), "session"), 1)
        val queries = mockk<ConfigurationQueryService> {
            every { observeWebDavConfiguration() } returns flowOf(WebDavConfigurationReadState.Available(selection, emptyList(), null))
            every { observeSelectedRealmSelection() } returns flowOf(selection)
        }
        lateinit var vm: WebDavSettingsVM
        compose.runOnUiThread { vm = WebDavSettingsVM(queries, mockk(), mockk()).also { store.put("settings", it) } }
        show { SettingWebDavPage(vm) }
        compose.onNodeWithText(text(R.string.webdav_enterprise_not_configured)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.webdav_no_current)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.webdav_add_connection)).assertDoesNotExist()
        capture("webdav-enterprise-unprovisioned.png")
    }

    @Test fun enterpriseBackupRequiresExplicitResourceAndKeepsDesignatedCurrent() {
        val selection = RealmSelection(RealmAccess.Enterprise(ConfigurationScope.Enterprise(
            EnterpriseAuthority("dep_12345678-1234-4234-8234-123456789abc"), "usr_12345678-1234-4234-8234-123456789abc"), "session"), 1)
        val original = WebDavConfigurationReadState.Available(selection, emptyList(), null,
            enterpriseResources = listOf(WebDavResourceSummary("bound", "Enterprise files", true, true, "3"),
                WebDavResourceSummary("unbound", "Other service", false, false, "1", "webdav_binding_unavailable")),
            designatedId = "bound")
        val configuration = MutableStateFlow<WebDavConfigurationReadState>(original)
        val vm = mockk<BackupVM> {
            every { settings } returns MutableStateFlow(Settings())
            every { webDavConfiguration } returns configuration
            every { webDavReady } returns MutableStateFlow(false)
            every { webDavTargetIdentity } returns MutableStateFlow(null)
            every { webDavIssue } returns MutableStateFlow(null)
            every { webDavBackupItems } returns MutableStateFlow(UiState.Idle)
            coEvery { saveWebDavPreferences(any(), any()) } coAnswers {
                configuration.value = original.copy(backup = secondArg())
            }
        }
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                androidx.compose.material3.Scaffold { padding ->
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(padding)) {
                        WebDavTab(vm) {}
                    }
                }
            }
        }
        compose.onNodeWithText(text(R.string.webdav_backup_select)).assertIsDisplayed().performClick()
        compose.onNodeWithText("Other service").assertIsNotEnabled()
        compose.onNodeWithText("Enterprise files").performClick()
        compose.onNodeWithText(text(R.string.webdav_save)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { vm.saveWebDavPreferences(original, WebDavBackupPreferences(resourceId = "bound")) }
        val saved = configuration.value as WebDavConfigurationReadState.Available
        assertEquals("bound", saved.backup.resourceId)
        assertEquals(original.currentId, saved.currentId)
        compose.onNodeWithText(text(R.string.webdav_root_url)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.backup_page_password)).assertDoesNotExist()
        capture("webdav-enterprise-backup-large-font.png")
    }
}
