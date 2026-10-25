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

    @Test fun linkedHtmlViewsRestoreEachScrollPositionWhenReturningThroughHistory() {
        val files = (0..2).map { index -> RenderedFileSource("page$index.html", MutableStateFlow(Unit), {}, { it }, { _, _ -> error("unexpected resource") }) }
        val states = (0..2).map { FilePreviewState() }
        var selected by mutableIntStateOf(0)
        compose.setContent { Host { key(selected) {
            FileDocumentPreview(files[selected], "<div style='height:2400px'>Page $selected</div><a id='next' href='page${selected + 1}.html'>Next page</a><div style='height:8000px'></div>",
                "page$selected.html", Modifier.fillMaxSize(), state = states[selected], onFileLink = { _, before -> before(); selected++ })
        } } }
        repeat(2) { index ->
            awaitScript("window.scrollTo(0,2400); window.scrollY.toString()") {
                it.contains("2400") && states[index].webScroll.y > 0
            }
            tapHtmlElement("next")
            compose.waitUntil(5_000) { selected == index + 1 }
        }
        awaitScript("document.body.innerText") { it.contains("Page 2") }
        for (index in 1 downTo 0) {
            compose.runOnIdle { selected = index }
            awaitScript("document.body.innerText + '|' + window.scrollY") { it.contains("Page $index") && it.endsWith("|2400\"") }
        }
    }

    @Test fun markdownPositionSurvivesDisposalAndSourceModeRoundTrip() {
        val file = RenderedFileSource("note.md", MutableStateFlow(Unit), {}, { it }, { _, _ -> error("unexpected resource") })
        val state = FilePreviewState()
        var visible by mutableStateOf(true)
        compose.setContent { Host {
            if (visible) FileDocumentPreview(file, (1..100).joinToString("\n\n") { "Paragraph $it" }, "note.md", Modifier.fillMaxSize(), state = state)
        } }
        compose.waitUntil(5_000) { state.markdownScroll.maxValue > 1500 }
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state.markdownScroll.scrollTo(1500) } }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitUntil(5_000) { state.markdownScroll.maxValue > 1500 }
        compose.runOnIdle { assertEquals(1500, state.markdownScroll.value) }
    }

    @Test fun htmlImageGeometryKeepsDeepPositionWhileReturningImagesAreStillLoading() {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 400, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        var delayImages = false
        val file = RenderedFileSource("gallery.html", MutableStateFlow(Unit), {}, { it }, { _, _ ->
            if (delayImages) resume.await()
            bytes
        })
        val html = (0..7).joinToString("") { "<img src='image$it.png' width='120' style='display:block'>" }
        var state = FilePreviewState()
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { Host {
            val current = androidx.compose.runtime.saveable.rememberSaveable(saver = FilePreviewState.Saver) { FilePreviewState() }
            SideEffect { state = current }
            FileDocumentPreview(file, html, "gallery.html", Modifier.fillMaxSize(), state = current)
        } }
        try {
            repeat(8) { index ->
                awaitScript("document.images[$index].scrollIntoView(); 'scrolled'") { state.webScroll.imageSizes().containsKey(index) }
            }
            awaitScript("document.readyState") { it.contains("complete") }
            awaitScript("window.scrollTo(0,7000); window.scrollY.toString()") { it.contains("7000") && state.webScroll.y > 0 }
            compose.runOnIdle {
                val view = requireNotNull(webView(compose.activity.window.decorView))
                assertEquals(view.scrollY, state.webScroll.y)
                @Suppress("DEPRECATION")
                assertEquals(7000f, state.webScroll.y / view.scale, 1f)
            }
            delayImages = true
            val previous = state
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(5_000) { state !== previous }
            awaitScript("JSON.stringify({y:scrollY,height:document.documentElement.scrollHeight,ready:document.readyState})") { it.contains("7000") }
            assertFalse(resume.isCompleted)
            resume.complete(Unit)
            awaitScript("document.readyState + '|' + window.scrollY") { it.contains("complete|7000") }
        } finally { resume.complete(Unit) }
    }

    @Test fun markdownImageGeometryKeepsPositionBeforeImagesReload() {
        val bitmap = android.graphics.Bitmap.createBitmap(400, 1600, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pending = java.util.concurrent.atomic.AtomicInteger()
        var delayImages = false
        val file = RenderedFileSource("gallery.md", MutableStateFlow(Unit), {}, { it }, { _, _ ->
            if (delayImages) { pending.incrementAndGet(); resume.await() }
            bytes
        })
        val state = FilePreviewState()
        var visible by mutableStateOf(true)
        val text = (0..7).joinToString("\n\n") { "![Image $it](image$it.png)" }
        compose.setContent { Host { if (visible) FileDocumentPreview(file, text, "gallery.md", Modifier.fillMaxSize(), state = state) } }
        try {
            compose.waitUntil(10_000) { state.markdownScroll.maxValue > 2000 }
            compose.runOnIdle { kotlinx.coroutines.runBlocking { state.markdownScroll.scrollTo(2000) } }
            compose.waitForIdle()
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            delayImages = true
            compose.runOnIdle { visible = true }
            compose.waitUntil(10_000) { pending.get() > 0 }
            compose.runOnIdle { assertEquals(2000, state.markdownScroll.value) }
            resume.complete(Unit)
        } finally { resume.complete(Unit) }
    }

    @Test fun dismissingResourceFailuresDoesNotHideLaterFailures() {
        val file = RenderedFileSource("index.html", MutableStateFlow(Unit), {}, { it }, { path, _ -> throw java.io.FileNotFoundException(path) })
        fun failResource(path: String) = kotlinx.coroutines.runBlocking {
            try { file.readResource(path); fail("expected missing resource") }
            catch (_: net.weero.measix.pilot.service.RenderedResourceException) { }
        }
        failResource("first.css")
        var retried = false
        compose.setContent { Host { FilePreviewResourceStatus(file) { retried = true } } }
        val title = compose.activity.getString(net.weero.measix.pilot.R.string.file_preview_resources_failed)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(net.weero.measix.pilot.R.string.chat_page_dismiss_error)).performClick()
        failResource("first.css")
        compose.onNodeWithText(title).assertDoesNotExist()
        failResource("second.css")
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(title).assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.application_recovery_retry)).performClick()
        assertTrue(retried)
    }

    @Test fun offscreenImagesPreserveSingleDimensionAndAuthorMinimumHeight() {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 80, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
        }
        bitmap.recycle()
        val file = RenderedFileSource("index.html", MutableStateFlow(Unit), {}, { it }, { _, _ -> bytes })
        show(file, "<img id='single' src='one.png' width='120'><img id='styled' src='two.png' style='width:120px;height:auto;min-height:300px'><div style='height:12000px'></div>", "index.html")
        awaitScript("Array.from(document.images).every(i => i.naturalWidth === 40).toString()") { it.contains("true") }
        val sizes = "[document.getElementById('single').offsetHeight,document.getElementById('styled').offsetHeight].join(',')"
        awaitScript(sizes) { it.contains("240,300") }
        var scrollHeight = ""
        awaitScript("document.body.scrollHeight.toString()") { scrollHeight = it; it.trim('"').toIntOrNull() != null }
        awaitScript("window.scrollTo(0, document.body.scrollHeight); 'scrolled'") { it.contains("scrolled") }
        awaitScript("Array.from(document.images).every(i => i.dataset.previewActive !== '1' && i.src.includes('/.measix-preview-placeholder.svg?')).toString()") { it.contains("true") }
        awaitScript(sizes) { it.contains("240,300") }
        awaitScript("document.body.scrollHeight.toString()") { it == scrollHeight }
    }

    @Test fun cssImageSetCannotBypassInlineResourceValidation() {
        val file = RenderedFileSource("index.html", MutableStateFlow(Unit), {}, { it }, { _, _ -> error("unexpected read") })
        show(file, "<div id='target' style='width:100px;height:100px'></div>", "index.html")
        awaitScript("""(() => {
            if (!document.getElementById('target')) return false;
            window.inlineBlocked = false;
            document.addEventListener('securitypolicyviolation', e => {
                if (e.violatedDirective === 'img-src' && e.blockedURI === 'data') window.inlineBlocked = true;
            });
            document.getElementById('target').style.backgroundImage = 'image-set("data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%22100%22 height=%22100%22/%3E" 1x)';
            return true;
        })().toString()""") { it.contains("true") }
        awaitScript("window.inlineBlocked.toString()") { it.contains("true") }
    }

    @Test fun longGalleryRendersPastOldCountAndCumulativePixelLimits() {
        val bitmap = android.graphics.Bitmap.createBitmap(400, 400, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val file = RenderedFileSource("gallery/index.html", MutableStateFlow(Unit), {}, { it }, { _, _ ->
            reads.incrementAndGet()
            bytes
        })
        val html = (0 until 160).joinToString("") { "<img src='thumb-$it.png' width='40' height='40'>" }
        show(file, html, "index.html")
        awaitScript("Array.from(document.images).filter(i => i.complete && i.naturalWidth === 400).length.toString()") {
            it.contains("160")
        }
        assertEquals(160, reads.get())
        compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.file_preview_resources_failed)).assertDoesNotExist()
    }

    @Test fun offscreenImagesWaitUntilNearViewportAndScrollingBackUsesBoundedCache() {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
        }
        bitmap.recycle()
        val reads = CopyOnWriteArrayList<String>()
        val file = RenderedFileSource("gallery/index.html", MutableStateFlow(Unit), {}, { it }, { path, _ -> reads += path; bytes })
        show(file, "<img id='first' src='first.png' width='40' height='40'><div style='height:12000px'></div><img id='last' src='last.png' width='40' height='40'>", "index.html")
        awaitScript("(() => { const i = document.getElementById('first'); return i.naturalWidth === 40 && i.dataset.previewActive === '1' && i.currentSrc.endsWith('/first.png'); })().toString()") { it.contains("true") }
        assertTrue(reads.contains("gallery/first.png"))
        assertFalse(reads.contains("gallery/last.png"))
        awaitScript("window.scrollTo(0, document.body.scrollHeight); 'scrolled'") { it.contains("scrolled") }
        awaitScript("document.getElementById('last').naturalWidth.toString()") { it.contains("40") }
        awaitScript("(document.getElementById('first').dataset.previewActive === '1').toString()") { it.contains("false") }
        awaitScript("window.scrollTo(0, 0); 'returned'") { it.contains("returned") }
        awaitScript("(() => { const i = document.getElementById('first'); return i.naturalWidth === 40 && i.dataset.previewActive === '1' && i.currentSrc.endsWith('/first.png'); })().toString()") { it.contains("true") }
        assertEquals(1, reads.count { it == "gallery/first.png" })
    }

    @Test fun fullscreenFileLinkKeepsOriginalDirectoryAndReturnsBeforeNativeNavigation() {
        val stack = mutableStateListOf<androidx.navigation3.runtime.NavKey>(Screen.Enterprise)
        val navigator = Navigator(stack)
        val file = RenderedFileSource("docs/note.md", MutableStateFlow(Unit), {}, { it }, { _, _ -> error("unexpected resource") })
        var opened: String? = null
        compose.setContent { Host {
            CompositionLocalProvider(LocalNavController provides navigator) {
                val current = stack.last()
                if (current is Screen.ContentPreview) net.weero.measix.pilot.ui.pages.webview.ContentPreviewPage(current.document, current.onFileLink)
                else FileDocumentPreview(file, "```html\n<a id='target' href='../next.md'>Open linked file</a>\n```", "note.md",
                    Modifier.fillMaxSize(), onFileLink = { path, before ->
                        before()
                        assertEquals(1, stack.size)
                        opened = path
                    })
            }
        } }
        compose.onNodeWithContentDescription(compose.activity.getString(net.weero.measix.pilot.R.string.code_block_fullscreen_preview)).performClick()
        compose.waitUntil(5_000) { stack.last() is Screen.ContentPreview }
        tapHtmlElement("target")
        compose.waitUntil(5_000) { opened == "next.md" }
        assertEquals(1, stack.size)
    }

    @Test fun markdownAnchorUsesDisplayedLinkedHeadingText() {
        val file = RenderedFileSource("note.md", MutableStateFlow(Unit), {}, { it }, { _, _ -> error("unexpected read") })
        show(file, "[跳转](#安装)\n\n" + (1..80).joinToString("\n\n") { "Paragraph $it" } + "\n\n# [安装](guide.md)\n\nEnd", "note.md")
        compose.onNodeWithText("跳转").performTouchInput { click(androidx.compose.ui.geometry.Offset(10f, centerY)) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("安装").assertIsDisplayed() }.isSuccess }
    }

    @Test fun fullscreenMissingFileLinkShowsDismissibleDiagnostic() {
        val file = RenderedFileSource("docs/note.html", MutableStateFlow(Unit), {}, { it }, { _, _ -> error("unexpected read") })
        compose.setContent { Host {
            net.weero.measix.pilot.ui.pages.webview.ContentPreviewPage(
                net.weero.measix.pilot.service.RenderedContent(
                    net.weero.measix.pilot.service.RenderedContentSource.WorkspaceFile(file),
                    "<a id='missing' href='missing.md'>Missing file</a>"),
                onFileLink = { _, _ -> error("workspace_file_missing: docs/missing.md") })
        } }
        tapHtmlElement("missing")
        compose.waitUntil(5_000) { runCatching {
            compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.chat_message_attachment_open_failed)).assertIsDisplayed()
        }.isSuccess }
        compose.onNodeWithContentDescription(compose.activity.getString(net.weero.measix.pilot.R.string.chat_page_dismiss_error)).performClick()
        compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.chat_message_attachment_open_failed)).assertDoesNotExist()
    }

    private fun tapHtmlElement(id: String) {
        var points = listOf<Float>()
        awaitScript("(() => { const r=document.getElementById('$id').getBoundingClientRect(); return [r.left+r.width/2,r.top+r.height/2,innerWidth].join(','); })()") {
            points = it.trim('"').split(',').mapNotNull(String::toFloatOrNull)
            points.size == 3
        }
        compose.runOnUiThread {
            val view = requireNotNull(webView(compose.activity.window.decorView))
            val scale = view.width / points[2]
            val time = android.os.SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                val event = android.view.MotionEvent.obtain(time, time + if (action == android.view.MotionEvent.ACTION_UP) 50 else 0,
                    action, points[0] * scale, points[1] * scale, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    @Test fun fileMarkdownTableKeepsCopyAfterReplacingRestrictedRenderer() {
        val file = RenderedFileSource("note.md", MutableStateFlow(Unit), {}, { it }, { _, _ -> byteArrayOf() })
        show(file, "| Action | Result |\n| --- | --- |\n| Save | Version |", "note.md")
        compose.onAllNodesWithContentDescription(compose.activity.getString(net.weero.measix.pilot.R.string.copy))
            .onFirst().performClick()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.contains("| Save | Version |") == true }
    }

    @Test fun htmlLoadsRelativeCssWithoutExecutingScriptThenRevocationRemovesStaticDocument() {
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
            it.contains("initial") && it.contains("rgb(12, 34, 56)")
        }
        assertTrue(reads.contains("assets/theme.css"))
        assertFalse(reads.contains("pages/app.js"))
        compose.runOnIdle { assertTrue(requireNotNull(webView(compose.activity.window.decorView)).settings.javaScriptEnabled) }
        compose.runOnIdle { allowed.value = false }
        compose.waitUntil(5_000) { webView(compose.activity.window.decorView) == null }
        compose.onNodeWithText("rendered_file_revoked", substring = true).assertExists()
    }

    @Test fun missingStylesheetKeepsBodyAndRetryLoadsFreshResources() {
        var available = false
        val file = RenderedFileSource("index.html", MutableStateFlow(Unit), {}, { it }, { path, _ ->
            if (!available || path != "theme.css") throw java.io.FileNotFoundException(path)
            "#result{color:rgb(12,34,56)}".toByteArray()
        })
        show(file, "<link rel='icon' href='favicon.ico'><link rel='stylesheet' href='theme.css'><p id='result'>Still readable</p>", "index.html")
        awaitScript("document.body.innerText") { it.contains("Still readable") }
        compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.file_preview_resources_failed)).assertExists()
        compose.runOnIdle { available = true }
        compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.application_recovery_retry)).performClick()
        awaitScript("getComputedStyle(document.getElementById('result')).color") { it.contains("rgb(12, 34, 56)") }
        compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.file_preview_resources_failed)).assertDoesNotExist()
    }

    @Test fun leavingPreviewCancelsBlockedResourceAndReturningReadsFreshContent() {
        val started = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(1)
        var block = true
        val file = RenderedFileSource("index.html", MutableStateFlow(Unit), {}, { it }, { _, _ ->
            if (block) {
                started.countDown()
                try { kotlinx.coroutines.awaitCancellation() } finally { exited.countDown() }
            }
            "#result{color:rgb(12,34,56)}".toByteArray()
        })
        var preview by mutableStateOf(true)
        var body by mutableStateOf("before edit")
        compose.setContent { Host {
            if (preview) FileDocumentPreview(file, "<link rel='stylesheet' href='theme.css'><p id='result'>$body</p>", "index.html", Modifier.fillMaxSize())
            else androidx.compose.material3.Text("Editor draft")
        } }
        compose.waitUntil(10_000) { started.count == 0L }
        compose.runOnIdle { preview = false }
        compose.onNodeWithText("Editor draft").assertExists()
        assertTrue(exited.await(5, java.util.concurrent.TimeUnit.SECONDS))
        compose.runOnIdle { block = false; body = "after save"; preview = true }
        awaitScript("document.body.innerText") { it.contains("after save") && !it.contains("before edit") }
    }

    @Test fun slowResourceTimesOutWithoutLosingBodyAndCanRetry() {
        var slow = true
        val file = RenderedFileSource("index.html", MutableStateFlow(Unit), {}, { it }, { _, _ ->
            if (slow) kotlinx.coroutines.awaitCancellation()
            "p{color:rgb(12,34,56)}".toByteArray()
        })
        show(file, "<link rel='stylesheet' href='slow.css'><p>Timeout recovery</p>", "index.html")
        val message = compose.activity.getString(net.weero.measix.pilot.R.string.file_preview_resources_failed)
        compose.waitUntil(45_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        awaitScript("document.body.innerText") { it.contains("Timeout recovery") }
        compose.runOnIdle { slow = false }
        compose.onNodeWithText(compose.activity.getString(net.weero.measix.pilot.R.string.application_recovery_retry)).performClick()
        awaitScript("getComputedStyle(document.querySelector('p')).color") { it.contains("rgb(12, 34, 56)") }
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test fun inlineImagesAndNestedStylesheetsRenderThroughTheManagedResourcePath() {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().use { out ->
            bitmap.eraseColor(android.graphics.Color.GREEN)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        val reads = CopyOnWriteArrayList<String>()
        val file = RenderedFileSource("pages/index.html", MutableStateFlow(Unit), {}, { it }, { path, _ ->
            reads += path
            when (path) {
                "styles/theme.css" -> "@import 'nested/colors.css'; body{font-family:sans-serif;padding:20px;background:#f6f4ef} img{width:64px;height:64px}".toByteArray()
                "styles/nested/colors.css" -> "h1{color:rgb(12,34,56)}".toByteArray()
                else -> throw java.io.FileNotFoundException(path)
            }
        })
        val inline = "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        val svg = "data:image/svg+xml," + java.net.URLEncoder.encode("<svg xmlns='http://www.w3.org/2000/svg' width='8' height='8'><rect width='8' height='8' fill='#008000'/></svg>", "UTF-8").replace("+", "%20")
        show(file, "<link rel='stylesheet' href='../styles/theme.css'><h1>Workspace preview</h1><p>Static styles and images remain readable.</p><img id='image' src='$inline'><img id='vector' src='$svg'><p>Missing optional files do not replace this document.</p>", "index.html")
        awaitScript("document.getElementById('vector').naturalWidth + '|' + document.getElementById('image').naturalWidth + '|' + getComputedStyle(document.querySelector('h1')).color") {
            it.contains("8|8|rgb(12, 34, 56)")
        }
        assertTrue(reads.containsAll(listOf("styles/theme.css", "styles/nested/colors.css")))
        val drawn = java.util.concurrent.CountDownLatch(1)
        compose.runOnUiThread {
            requireNotNull(webView(compose.activity.window.decorView)).postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { drawn.countDown() }
            })
        }
        assertTrue(drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "workspace-static-preview.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun fullscreenOfflineMermaidRetainsItsExplicitRenderer() {
        val file = RenderedFileSource("docs/note.md", MutableStateFlow(Unit), {}, { it }, { _, _ -> error("Unexpected workspace read") })
        compose.setContent { Host {
            net.weero.measix.pilot.ui.pages.webview.ContentPreviewPage(net.weero.measix.pilot.service.RenderedContent(
                net.weero.measix.pilot.service.RenderedContentSource.WorkspaceFile(file),
                net.weero.measix.pilot.ui.components.richtext.buildMermaidHtml("graph TD\nA[Source] --> B[Preview]", MaterialTheme.colorScheme),
                offlineDiagram = true,
            ))
        } }
        awaitScript("document.querySelector('svg')?.textContent") { it.contains("Source") && it.contains("Preview") }
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
        compose.waitForIdle()
        var value = ""
        val pending = java.util.concurrent.atomic.AtomicBoolean(false)
        try { compose.waitUntil(15_000) {
            if (!pending.get() && accept(value)) return@waitUntil true
            compose.runOnUiThread { webView(compose.activity.window.decorView)?.let { view ->
                if (!pending.compareAndSet(false, true)) return@let
                // Test-only DOM inspection; page scripts have already been removed before loading.
                val enabled = view.settings.javaScriptEnabled
                view.settings.javaScriptEnabled = true
                view.evaluateJavascript(script) { view.settings.javaScriptEnabled = enabled; value = it; pending.set(false) }
            } }
            !pending.get() && accept(value)
        } } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("JavaScript condition timed out: $script; last result: $value", error)
        }
    }

    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) webView(view.getChildAt(i))?.let { return it }
        return null
    }
}
