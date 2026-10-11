package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.compositionLocalOf
import net.weero.measix.pilot.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
