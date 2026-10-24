package net.weero.measix.pilot.ui.components.richtext

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.service.RenderedContentSource
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownPreviewAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun astPreviewWaitsForClosureAndPreservesManualChoice() = exercise("")
    @Test fun htmlPreviewUsesTrustedClosureAndCopiesOriginalLineEndings() = exercise("<b>Intro</b>\r\n\r\n")
    @Test fun mermaidAstDefaultsToDiagramAndKeepsSourceChoice() = exerciseMermaid("")
    @Test fun mermaidHtmlPathDefaultsToDiagramAndKeepsSourceChoice() = exerciseMermaid("<b>Intro</b>\n\n")

    private fun exerciseMermaid(prefix: String) {
        var text by mutableStateOf(prefix + "```mermaid\ngraph TD\nA[Start] --> B[Finish]")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
                    net.weero.measix.pilot.ui.context.LocalToaster provides net.weero.measix.pilot.ui.components.toast.rememberToasterState(),
                    LocalRenderedContentSource provides RenderedContentSource.Static) { MarkdownBlock(text) }
            }
        }
        val source = compose.activity.getString(R.string.file_preview_source)
        val preview = compose.activity.getString(R.string.code_block_preview)
        compose.onNodeWithContentDescription(source).assertDoesNotExist()
        compose.runOnIdle { text += "\n```" }
        fun webView(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) webView(view.getChildAt(index))?.let { return it }
            return null
        }
        var diagram = ""
        compose.waitUntil(20_000) {
            compose.runOnUiThread { webView(compose.activity.window.decorView)?.evaluateJavascript("document.querySelector('svg')?.textContent") { diagram = it } }
            diagram.contains("Start") && diagram.contains("Finish")
        }
        compose.onNodeWithContentDescription(source).performClick()
        compose.onNodeWithContentDescription(preview).assertExists()
        compose.runOnIdle { text += "\n\nMore text" }
        compose.onNodeWithContentDescription(preview).assertExists()
        compose.runOnIdle { assertEquals(null, webView(compose.activity.window.decorView)) }
    }

    private fun exercise(prefix: String) {
        val text = mutableStateOf(prefix + "````html\r\n<div>first\r\nsecond</div>")
        val loading = mutableStateOf(true)
        val preferences = CodePreviewPreferences()
        compose.setContent {
            preferences.observe(text.value, loading.value)
            MaterialTheme {
                CompositionLocalProvider(
                    LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
                    LocalRenderedContentSource provides RenderedContentSource.Static,
                    LocalCodePreviewPreferences provides preferences,
                ) {
                    if (loading.value) MarkdownBlock(text.value)
                    else SelectionContainer { MarkdownBlock(text.value) }
                }
            }
        }
        val preview = compose.activity.getString(R.string.code_block_preview)
        val source = compose.activity.getString(R.string.file_preview_source)
        compose.onNodeWithContentDescription(source).assertDoesNotExist()
        compose.onNodeWithContentDescription(preview).assertDoesNotExist()
        compose.runOnIdle { text.value += "\r\n````" }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(source).fetchSemanticsNodes().size == 1 }
        var body: String? = null
        fun findWebView(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                findWebView(compose.activity.window.decorView)?.evaluateJavascript("document.body.innerText") { body = it }
            }
            body?.contains("first") == true && body?.contains("second") == true
        }
        compose.onNodeWithContentDescription(source).performClick()
        compose.onNodeWithContentDescription(preview).assertExists()
        compose.runOnIdle { text.value += "\r\n\r\nFollowing text" }
        compose.onNodeWithContentDescription(preview).assertExists()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithContentDescription(preview).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.code_block_copy)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("<div>first\r\nsecond</div>", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.runOnIdle { text.value = prefix + "````svg\r\n<svg></svg>\r\n````" }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(source).fetchSemanticsNodes().size == 1 }
    }
}
