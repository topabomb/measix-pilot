package net.weero.measix.pilot.ui.components.files

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import kotlinx.coroutines.flow.MutableStateFlow
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.service.RenderedFileSource
import net.weero.measix.pilot.ui.context.*
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class FileDocumentPreviewAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fileMarkdownTableKeepsCopyAfterReplacingRestrictedRenderer() {
        val file = RenderedFileSource("note.md", MutableStateFlow(Unit), {}, { it }, { _, _ -> byteArrayOf() })
        show(file, "| Action | Result |\n| --- | --- |\n| Save | Version |", "note.md")
        compose.onAllNodesWithContentDescription(compose.activity.getString(net.weero.measix.pilot.R.string.copy))
            .onFirst().performClick()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.contains("| Save | Version |") == true }
    }

    @Test fun htmlLoadsRelativeCssAndScriptThenRevocationRemovesStaticDocument() {
        val allowed = MutableStateFlow(true)
        val reads = CopyOnWriteArrayList<String>()
        val file = RenderedFileSource("pages/index.html", allowed,
            verify = { check(allowed.value) { "rendered_file_revoked" } }, validatePath = { it },
            read = { path, _ ->
                reads += path
                when (path) {
                    "assets/theme.css" -> "#result { color: rgb(12, 34, 56); }"
                    "pages/app.js" -> "document.getElementById('result').textContent = 'script loaded';"
                    else -> throw java.io.FileNotFoundException(path)
                }.toByteArray()
            })
        show(file, """<base href="https://untrusted.invalid/"><link rel="stylesheet" href="../assets/theme.css?v=1"><div id="result">initial</div><script src="app.js"></script>""", "index.html")
        awaitScript("document.body.innerText + '|' + getComputedStyle(document.getElementById('result')).color") {
            it.contains("script loaded") && it.contains("rgb(12, 34, 56)")
        }
        assertTrue(reads.containsAll(listOf("assets/theme.css", "pages/app.js")))
        compose.runOnIdle { allowed.value = false }
        compose.waitUntil(5_000) { webView(compose.activity.window.decorView) == null }
        compose.onNodeWithText("rendered_file_revoked", substring = true).assertExists()
    }

    @Test fun markdownHtmlFenceAndSvgUseTheSamePreviewPath() {
        val file = RenderedFileSource("docs/note.md", MutableStateFlow(Unit), {}, { it }, { _, _ -> byteArrayOf() })
        var text by mutableStateOf("```html\n<div id='result'>markdown html</div>\n```")
        var name by mutableStateOf("note.md")
        compose.setContent { Host { FileDocumentPreview(file, text, name, Modifier.fillMaxSize()) } }
        awaitScript("document.body.innerText") { it.contains("markdown html") }
        compose.runOnIdle { text = "```mermaid\ngraph TD\nA[File] --> B[Preview]\n```" }
        awaitScript("document.querySelector('svg')?.textContent") { it.contains("File") && it.contains("Preview") }
        compose.runOnIdle {
            name = "drawing.svg"
            text = "<svg xmlns='http://www.w3.org/2000/svg'><text x='10' y='30'>svg preview</text></svg>"
        }
        awaitScript("document.querySelector('svg')?.textContent") { it.contains("svg preview") }
    }

    private fun show(file: RenderedFileSource, text: String, name: String) {
        compose.setContent { Host { FileDocumentPreview(file, text, name, Modifier.fillMaxSize()) } }
    }

    @Composable private fun Host(content: @Composable () -> Unit) {
        MaterialTheme {
            CompositionLocalProvider(LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
                LocalToaster provides rememberToasterState(), LocalNavController provides Navigator(mutableListOf(Screen.Enterprise)),
                content = content)
        }
    }

    private fun awaitScript(script: String, accept: (String) -> Boolean) {
        var value = ""
        compose.waitUntil(15_000) {
            compose.runOnUiThread { webView(compose.activity.window.decorView)?.evaluateJavascript(script) { value = it } }
            accept(value)
        }
    }

    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) webView(view.getChildAt(i))?.let { return it }
        return null
    }
}
