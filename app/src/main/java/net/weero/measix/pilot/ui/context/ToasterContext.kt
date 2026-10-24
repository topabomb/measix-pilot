package net.weero.measix.pilot.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import net.weero.measix.pilot.ui.components.toast.ToasterState

val LocalToaster = staticCompositionLocalOf<ToasterState> { error("Not provided") }