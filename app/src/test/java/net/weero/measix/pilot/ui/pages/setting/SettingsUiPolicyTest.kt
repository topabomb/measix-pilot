package net.weero.measix.pilot.ui.pages.setting

import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

import net.weero.measix.pilot.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings-page UI policy: MCP header-index remapping after a row removal, and the display-setting
 * delta merge that applies only edited fields without clobbering concurrent updates.
 */
class SettingsUiPolicyTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `settings view model exposes loaded owner snapshot immediately without a default frame`() {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        try {
            val loaded = net.weero.measix.pilot.data.datastore.Settings(dynamicColor = false, enableSuggestion = false)
            val state = kotlinx.coroutines.flow.MutableStateFlow(loaded)
            val store = io.mockk.mockk<net.weero.measix.pilot.data.datastore.SettingsStore>()
            io.mockk.every { store.userSettings } returns state
            val query = io.mockk.mockk<net.weero.measix.pilot.service.ConfigurationQueryService>()
            io.mockk.every { query.observeModelCatalog() } returns kotlinx.coroutines.flow.flowOf(net.weero.measix.pilot.service.ModelCatalogReadState.Loading)
            val vm = SettingVM(store, io.mockk.mockk(), io.mockk.mockk(), query, io.mockk.mockk(), io.mockk.mockk())
            assertEquals(loaded, vm.settings.value)
            org.junit.Assert.assertFalse(vm.settings.value.init)
            state.value = loaded.copy(enableSuggestion = true)
            assertEquals(state.value, vm.settings.value)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test
    fun `header removal preserves visibility of surviving headers`() {
        listOf(
            Triple(setOf(1), 1, emptySet<Int>()),
            Triple(setOf(0, 2, 4), 1, setOf(0, 1, 3)),
            Triple(setOf(0, 1), 3, setOf(0, 1)),
        ).forEach { (visible, removed, expected) ->
            assertEquals(
                "visible=$visible, removed=$removed",
                expected,
                remapVisibleHeaderIndicesAfterRemoval(visible, removedIndex = removed),
            )
        }
    }

    @Test
    fun `page delta applies edited fields without overwriting concurrent fields`() {
        val baseline = DisplaySetting()
        val edited = baseline.copy(
            showModelName = false,
            fontSizeRatio = 1.25f,
        )
        val current = baseline.copy(
            enableBlurEffect = true,
            pasteLongTextThreshold = 2048,
        )

        val merged = mergeDisplaySettingDelta(baseline, edited, current)

        assertEquals(false, merged.showModelName)
        assertEquals(1.25f, merged.fontSizeRatio)
        assertEquals(true, merged.enableBlurEffect)
        assertEquals(2048, merged.pasteLongTextThreshold)
    }

    @Test
    fun `unchanged page snapshot preserves the latest display settings`() {
        val baseline = DisplaySetting()
        val current = baseline.copy(
            showLineNumbers = true,
            volumeKeyScrollRatio = 2.0f,
        )

        assertEquals(current, mergeDisplaySettingDelta(baseline, baseline, current))
    }

    @Test
    fun `update pause timestamp is merged independently of showUpdates`() {
        val baseline = DisplaySetting()
        val edited = baseline.copy(updateCheckDisabledUntilEpochMillis = 9_000L)
        val current = baseline.copy(showUpdates = false)

        val merged = mergeDisplaySettingDelta(baseline, edited, current)

        assertEquals(9_000L, merged.updateCheckDisabledUntilEpochMillis)
        assertEquals(false, merged.showUpdates)
    }
}
