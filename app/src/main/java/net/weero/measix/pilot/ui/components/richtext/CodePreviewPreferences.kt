package net.weero.measix.pilot.ui.components.richtext

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/** UI choices belong to a message text part, across parser and selection-container changes. */
internal class CodePreviewPreferences {
    private val overrides = mutableStateMapOf<Any, Boolean>()
    private var previousText: String? = null
    private var wasStreaming = true

    fun observe(text: String, streaming: Boolean) {
        val previous = previousText
        if (previous != null && text != previous && !(wasStreaming && text.startsWith(previous))) {
            overrides.clear()
        }
        previousText = text
        wasStreaming = streaming
    }

    fun showPreview(identity: Any, eligible: Boolean): Boolean = eligible && overrides[identity] != false
    fun choose(identity: Any, preview: Boolean) { overrides[identity] = preview }
}

internal data class MarkdownFenceIdentity(val startOffset: Int, val language: String)
internal val LocalCodePreviewPreferences = staticCompositionLocalOf<CodePreviewPreferences?> { null }
