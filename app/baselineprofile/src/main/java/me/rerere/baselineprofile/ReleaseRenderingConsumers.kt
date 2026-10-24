package me.rerere.baselineprofile

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The runner stays outside the shipping process. All configuration and messages use its real UI. */
@RunWith(AndroidJUnit4::class)
class ReleaseRenderingConsumers {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun shippingChatRendersSelectedResponse() {
        val arguments = InstrumentationRegistry.getArguments()
        val target = requireNotNull(arguments.getString("targetAppId"))
        require(target == "net.weero.measix.pilot")
        require(instrumentation.targetContext.packageName != target)
        val info = instrumentation.context.packageManager.getApplicationInfo(target, 0)
        require(info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val kind = requireNotNull(arguments.getString("renderCase")) {
            "Pass renderCase=html|svg|mermaid|inline-html|latex-macro|latex-greek|latex-cyrillic|latex-complex|latex-escaping|latex-invalid"
        }
        val marker = "PROFILE_${UUID.randomUUID().toString().replace("-", "") }"
        val providerName = "Release provider $marker"
        val modelName = "Release model $marker"
        val source = source(kind, marker)
        var providerOwned = false
        var chatOwned = false
        var failure: Throwable? = null
        ProfileChatServer { requestMarker ->
            check(requestMarker == marker) { "Unexpected request identity" }
            val content = if (kind == "inline-html") source else if (kind in setOf("html", "svg", "mermaid")) "```$kind\n$source\n```" else
                "${'$'}${'$'}\n$source\n${'$'}${'$'}"
            "$content\n\nPROFILE_REPLY_DONE_$marker"
        }.use { server ->
            try {
                openProviders(target)
                openProviderImportAction()
                click(By.text("Paste Config"))
                await(By.clazz("android.widget.EditText")).text = providerImport(providerName, modelName, marker, server.port)
                click(By.text("Import Provider Configuration"))
                await(By.text(providerName))
                providerOwned = true
                openNewChatAndSelectModel(target, modelName)
                await(By.res("chat_input")).text = marker
                click(By.res("chat_send_button").enabled(true))
                chatOwned = true
                await(By.textContains("PROFILE_REPLY_DONE_$marker"), 60_000)
                // The reply tail precedes the terminal SSE event. With an empty draft, the real
                // SendButton stays enabled as Stop until generation exits, then becomes disabled.
                await(By.res("chat_send_button").enabled(false))
                server.checkHealthy()
                if (kind in setOf("html", "svg", "mermaid")) {
                    await(By.desc("Source"))
                    awaitRenderedContent(kind, marker)
                    evidence("$kind-default-$marker")
                    click(By.desc("Source"))
                    await(By.desc("Preview"))
                    await(By.textContains(source))
                    evidence("$kind-source-$marker")
                    click(By.desc("Preview"))
                    await(By.desc("Source"))
                    awaitRenderedContent(kind, marker)
                } else if (kind == "inline-html") {
                    await(By.text("INLINE HTML $marker"))
                    assertFalse(device.hasObject(By.textContains("<b>")))
                } else if (kind == "latex-invalid") {
                    await(By.textContains(source))
                } else {
                    assertFalse("Valid formula fell back to source text", device.hasObject(By.textContains(source)))
                }
                // Canvas formula appearance and WebView graphics require review of these real screenshots.
                evidence("$kind-result-$marker")
            } catch (error: Throwable) {
                failure = error
                evidence("$kind-failure-$marker", error)
                throw error
            } finally {
                var cleanupFailure: Throwable? = null
                if (chatOwned) try {
                    deleteOwnedChat(marker)
                } catch (error: Throwable) { cleanupFailure = error }
                if (providerOwned) try {
                    openProviders(target)
                    click(By.text(providerName))
                    // Verify the unique provider identity before touching its destructive control.
                    await(By.text(providerName))
                    openProviderDeleteAction()
                    await(By.text("Confirm Delete"))
                    click(By.text("Delete"))
                    assertTrue(device.wait(Until.gone(By.text(providerName)), 20_000))
                } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
                }
                cleanupFailure?.let {
                    evidence("$kind-cleanup-$marker", it)
                    if (failure == null) throw it else failure.addSuppressed(it)
                }
            }
        }
    }

    private fun awaitRenderedContent(kind: String, marker: String) {
        await(By.clazz("android.webkit.WebView"))
        val label = when (kind) {
            "html" -> "HTML $marker"
            "svg" -> "SVG $marker"
            "mermaid" -> "Release finish"
            else -> error("Not a WebView render case: $kind")
        }
        await(By.text(label))
        device.waitForIdle()
    }

    private fun providerImport(providerName: String, modelName: String, marker: String, port: Int): String {
        val model = JSONObject().put("modelId", "pilot-release-$marker").put("displayName", modelName)
            .put("type", "CHAT").put("inputModalities", JSONArray().put("TEXT"))
            .put("outputModalities", JSONArray().put("TEXT"))
            .put("abilities", JSONArray()).put("tools", JSONArray())
        val provider = JSONObject().put("type", "openai").put("name", providerName).put("enabled", true)
            .put("apiKey", "release-local").put("baseUrl", "http://127.0.0.1:$port/v1")
            .put("chatCompletionsPath", "/chat/completions").put("useResponseApi", false)
            .put("models", JSONArray().put(model))
        return "ai-provider:v1:" + Base64.encodeToString(provider.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun source(kind: String, marker: String): String = when (kind) {
        "html" -> "<html><body><h1>HTML $marker</h1><div style=\"width:100px;height:40px;background:#ff0000\"></div></body></html>"
        "svg" -> "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"240\" height=\"80\"><rect width=\"240\" height=\"80\" fill=\"#00aa00\"/><text x=\"10\" y=\"40\">SVG $marker</text></svg>"
        "mermaid" -> "graph LR\nA[Release start] --> B[Release finish]"
        "inline-html" -> "<b>INLINE HTML $marker</b>"
        "latex-escaping" -> """\begin{cases}x<y & a\\[4pt]x>y & b\end{cases}"""
        "latex-macro" -> """\newcommand{\pilotReleaseSquare}[1]{#1^{2}}\pilotReleaseSquare{x}"""
        "latex-greek" -> """\text{αβγΩ}"""
        "latex-cyrillic" -> """\text{Привет}"""
        "latex-complex" -> """\begin{aligned}\int_0^\infty e^{-x^2}\,dx &= \frac{\sqrt{\pi}}{2}\\[4pt] A &= \begin{pmatrix}1&2\\3&4\end{pmatrix}\end{aligned}"""
        "latex-invalid" -> """\pilotUnknownReleaseCommand{x}"""
        else -> error("Unknown renderCase: $kind")
    }

    private fun openProviders(target: String) {
        val launch = requireNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.context.startActivity(launch)
        click(By.desc("Open conversation navigation"))
        click(By.desc("Settings"))
        repeat(10) {
            device.waitForIdle()
            val label = device.findObject(By.text("Providers"))
            if (label != null) {
                var row = label
                while (!row.isClickable) row = requireNotNull(row.parent)
                val bounds = row.visibleBounds
                if (bounds.top >= device.displayHeight / 4 && bounds.bottom <= device.displayHeight * 7 / 10) {
                    click(By.text("Providers"))
                    await(By.desc("Add Provider"))
                    return
                }
                val start = device.displayHeight * 3 / 4
                val end = (start - (bounds.centerY() - device.displayHeight / 2))
                    .coerceIn(device.displayHeight / 4, device.displayHeight * 9 / 10)
                device.swipe(device.displayWidth / 2, start, device.displayWidth / 2, end, 24)
            } else {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 2, 24)
            }
        }
        error("Providers settings entry is not safely visible")
    }

    private fun openProviderImportAction() {
        clickUniqueButtonBeside(await(By.desc("Add Provider")))
        await(By.text("Import Provider Configuration"))
    }

    private fun openNewChatAndSelectModel(target: String, modelName: String) {
        val launch = requireNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.context.startActivity(launch)
        click(By.desc("New Chat"))
        click(By.desc("Chat Model"))
        await(By.clazz("android.widget.EditText")).text = modelName
        val model = By.clazz("android.widget.TextView").text(modelName)
        await(model)
        check(device.findObjects(model).size == 1) { "Model result is ambiguous: $modelName" }
        click(model)
        assertTrue(device.wait(Until.gone(By.clazz("android.widget.EditText").text(modelName)), 20_000))
        await(By.res("chat_input"))
    }

    private fun deleteOwnedChat(marker: String) {
        await(By.text(marker))
        check(device.findObjects(By.text(marker)).size == 1) { "Current user marker is ambiguous" }
        val navigation = await(By.desc("Open conversation navigation")).visibleBounds
        val options = await(By.desc("Chat Options")).visibleBounds
        val top = maxOf(navigation.top, options.top)
        val bottom = minOf(navigation.bottom, options.bottom)
        check(top < bottom && navigation.right < options.left) { "Chat header controls do not share a row" }
        val titles = device.findObjects(By.clazz("android.widget.TextView")).filter {
            val bounds = it.visibleBounds
            bounds.left >= navigation.right && bounds.right <= options.left &&
                bounds.centerY() in top..bottom
        }
        check(titles.size == 1) { "Expected exactly one chat title, found ${titles.size}" }
        var titleOwner = titles.single()
        while (!titleOwner.isClickable) titleOwner = requireNotNull(titleOwner.parent)
        titleOwner.click()
        await(By.text("Edit title"))
        await(By.clazz("android.widget.EditText")).text = marker
        click(By.text("Save"))
        assertTrue(device.wait(Until.gone(By.text("Edit title")), 20_000))
        click(By.desc("Open conversation navigation"))
        await(By.text(marker))
        val rows = device.findObjects(By.text(marker)).mapNotNull { label ->
            var owner: UiObject2? = label
            while (owner != null && !owner.isLongClickable) owner = owner.parent
            owner
        }
        check(rows.size == 1) { "Expected exactly one owned conversation row, found ${rows.size}" }
        rows.single().longClick()
        click(By.text("Delete"))
        // With no queued input the production command deletes immediately, without a second dialog.
        assertTrue("Owned conversation title remains after deletion", device.wait(Until.gone(By.text(marker)), 20_000))
    }

    private fun openProviderDeleteAction() {
        repeat(10) {
            device.waitForIdle()
            val save = device.findObject(By.text("Save"))
            if (save != null && save.visibleBounds.bottom < device.displayHeight * 9 / 10) {
                var reset = await(By.desc("Reset to default"))
                while (!reset.isClickable) reset = requireNotNull(reset.parent)
                val resetBounds = reset.visibleBounds
                val candidates = device.findObjects(By.clazz("android.widget.Button").clickable(true)).filter {
                    val bounds = it.visibleBounds
                    bounds.centerY() in resetBounds.top..resetBounds.bottom &&
                        bounds.right <= resetBounds.left &&
                        resetBounds.left - bounds.right <= bounds.width()
                }
                val nearestRight = candidates.maxOfOrNull { it.visibleBounds.right }
                val nearest = candidates.filter { it.visibleBounds.right == nearestRight }
                check(nearest.size == 1) { "Expected one Delete button immediately left of Reset, found ${nearest.size}" }
                nearest.single().click()
                device.waitForIdle()
                return
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 2, 24)
        }
        error("Current provider's Save/Delete action row is not visible")
    }

    private fun clickUniqueButtonBeside(anchor: UiObject2) {
        device.waitForIdle()
        var owner = anchor
        while (!owner.isClickable) owner = requireNotNull(owner.parent)
        val band = owner.visibleBounds
        // The shipping hierarchy exposes FileImport/Delete as a clickable Button beside Add Provider/Save.
        // The other buttons belong to separate rows; ambiguity must fail instead of guessing a tap.
        val buttons = device.findObjects(By.clazz("android.widget.Button").clickable(true)).filter {
            val bounds = it.visibleBounds
            bounds.centerY() in band.top..band.bottom &&
                !bounds.contains(band.centerX(), band.centerY())
        }
        check(buttons.size == 1) { "Expected one unlabeled action beside anchor, found ${buttons.size}" }
        buttons.single().click()
        device.waitForIdle()
    }

    private fun await(selector: BySelector, timeout: Long = 20_000): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing UI: $selector" }

    private fun click(selector: BySelector) {
        repeat(5) { attempt ->
            device.waitForIdle()
            try {
                val bounds = await(selector).visibleBounds
                android.os.SystemClock.sleep(200)
                var settled = await(selector)
                if (settled.visibleBounds != bounds) {
                    check(attempt < 4) { "UI target is still moving: $selector" }
                    return@repeat
                }
                while (!settled.isClickable) settled = requireNotNull(settled.parent)
                settled.click()
            } catch (stale: androidx.test.uiautomator.StaleObjectException) {
                if (attempt == 4) throw stale
                return@repeat
            }
            device.waitForIdle()
            return
        }
    }

    private fun evidence(name: String, error: Throwable? = null) {
        try {
            val folder = File(instrumentation.context.getExternalFilesDir(null), "release-rendering").apply { mkdirs() }
            device.takeScreenshot(File(folder, "$name.png"))
            device.dumpWindowHierarchy(File(folder, "$name.xml"))
            error?.let { File(folder, "$name.txt").writeText(it.stackTraceToString()) }
            Log.i("ReleaseRendering", "Evidence: ${folder.absolutePath}/$name", error)
        } catch (capture: Throwable) {
            if (error != null) error.addSuppressed(capture) else throw capture
        }
    }
}
