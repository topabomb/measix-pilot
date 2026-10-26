package me.rerere.baselineprofile

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Black-box consumers of the installed shipping APK; never installs or substitutes a target. */
@RunWith(AndroidJUnit4::class)
class ReleaseConsumers {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun webDavDrawerOpensAfterBackAndProcessRestart() {
        val target = shippingTarget()
        val marker = "release-dav-${UUID.randomUUID()}"
        var saveAttempted = false
        var failure: Throwable? = null
        LoopbackDav(marker).use { server ->
            try {
                restartShippingApp(target)
                openSettingsEntry(target, "WebDAV", By.text("Add server"))
                // A dedicated personal baseline avoids changing any existing current server or realm.
                await(By.text("No current WebDAV. Add or select a server."))
                check(!device.hasObject(By.text("Edit")) && !device.hasObject(By.text("Delete"))) {
                    "This consumer requires an empty personal DAV list"
                }
                click(By.text("Add server"))
                val editors = device.findObjects(By.clazz("android.widget.EditText"))
                    .sortedBy { it.visibleBounds.top }
                check(editors.size >= 2) { "WebDAV server editor missing" }
                editors[0].text = marker
                editors[1].text = "http://127.0.0.1:${server.port}/"
                saveAttempted = true
                click(By.text("Save"))
                assertTrue("WebDAV editor did not close", device.wait(Until.gone(By.text("Save")), 10_000))
                await(By.text(marker))
                // The first saved server becomes current through the real configuration command.
                await(By.text("Currently in use"))
                device.pressBack()
                device.pressBack()

                repeat(3) { round ->
                    if (round == 2) restartShippingApp(target)
                    click(By.desc("Open conversation navigation"))
                    click(By.desc("WebDAV file manager"))
                    await(By.text("$marker.txt"))
                    assertTrue(device.hasObject(By.text("WebDAV file manager")))
                    device.pressBack()
                    await(By.desc("Open conversation navigation"))
                }
                assertTrue("Expected a fresh browser session on each entry", server.listings.get() >= 3)
                server.checkHealthy()
            } catch (error: Throwable) {
                failure = error
                captureFailure(marker, error)
                throw error
            } finally {
                if (saveAttempted) {
                    try {
                        restartShippingApp(target)
                        openSettingsEntry(target, "WebDAV", By.text("Add server"))
                        device.findObject(By.text(marker))?.let { label ->
                            var card = label
                            while (!card.hasObject(By.text("Delete"))) card = requireNotNull(card.parent)
                            requireNotNull(card.findObject(By.text("Delete"))).click()
                            await(By.text("Remove $marker? Server files will be kept. Any selection using this server will become unavailable."))
                            click(By.clickable(true).hasDescendant(By.text("Delete")))
                        }
                        assertTrue("Temporary DAV configuration remains", device.wait(Until.gone(By.text(marker)), 10_000))
                        await(By.text("No current WebDAV. Add or select a server."))
                    } catch (cleanup: Throwable) {
                        captureFailure("$marker-cleanup", cleanup)
                        if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
    }

    /** A bounded read-only DAV endpoint in the independent consumer process. */
    private class LoopbackDav(marker: String) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var closed = false
        val listings = AtomicInteger()
        val port: Int get() = listener.localPort
        private val body = """<?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/$marker.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5</d:getcontentlength><d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>""".trimIndent().toByteArray(Charsets.UTF_8)

        init {
            executor.execute {
                try {
                    while (!closed) listener.accept().use { socket ->
                        active.set(socket)
                        if (closed) return@use
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String = buildString {
                            while (true) {
                                val byte = input.read()
                                check(byte >= 0) { "Unexpected DAV request end" }
                                if (byte == 10) break
                                if (byte != 13) append(byte.toChar())
                                check(length < 8192)
                            }
                        }
                        check(line() == "PROPFIND / HTTP/1.1") { "Only directory reads are accepted" }
                        var length = 0
                        var headers = 0
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            check(++headers < 100)
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        require(length in 0..4096)
                        repeat(length) { check(input.read() >= 0) }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                        listings.incrementAndGet()
                        active.compareAndSet(socket, null)
                    }
                } catch (error: Exception) {
                    if (!closed) failure.set(error)
                }
            }
        }

        fun checkHealthy() { failure.get()?.let { throw AssertionError("Loopback DAV failed", it) } }
        override fun close() {
            closed = true
            listener.close()
            active.getAndSet(null)?.close()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Loopback DAV did not stop" }
        }
    }

    @Test
    fun shippingUiRetainsFiveLanguagesAndRegionalFallbacks() {
        val target = shippingTarget()
        // This dedicated-device test must not replace a user's existing per-app locale selection.
        assertEquals("Expected the observed System locale baseline", "", appLocales(target))
        val expectations = linkedMapOf(
            "en-US" to "Chat Options",
            "zh-CN" to "聊天选项",
            "zh-SG" to "聊天选项",
            // Existing values-zh is Hans; Hant falls back to unqualified English.
            "zh-TW" to "Chat Options",
            "ja-JP" to "チャットオプション",
            "ko-KR" to "채팅 옵션",
            // Android's same-script resource matching uses the available ko-rKR translation.
            "ko" to "채팅 옵션",
            "ru-RU" to "Параметры чата",
            "fr-FR" to "Chat Options",
        )
        var currentTag = "baseline"
        var failure: Throwable? = null
        try {
            restartShippingApp(target)
            await(By.desc("Chat Options"))
            expectations.forEach { (tag, expected) ->
                currentTag = tag
                device.executeShellCommand("cmd locale set-app-locales $target --locales $tag")
                assertEquals("Android did not apply the requested app locale", tag, appLocales(target))
                restartShippingApp(target)
                await(By.desc(expected))
            }
        } catch (error: Throwable) {
            failure = error
            captureFailure("locale-$currentTag", error)
            throw error
        } finally {
            try {
                device.executeShellCommand("cmd locale set-app-locales $target")
                assertEquals("Per-app locales were not restored to System", "", appLocales(target))
                restartShippingApp(target)
                await(By.desc("Chat Options"))
            } catch (restore: Throwable) {
                captureFailure("locale-restore", restore)
                if (failure != null) failure.addSuppressed(restore) else throw restore
            }
        }
    }

    private fun appLocales(target: String): String {
        val output = device.executeShellCommand("cmd locale get-app-locales $target").trim()
        val lists = Regex("\\[([^\\[\\]]*)]").findAll(output).toList()
        check(lists.size == 1 && !output.contains("Error", ignoreCase = true)) {
            "Unrecognized Android locale response: $output"
        }
        return lists.single().groupValues[1]
    }

    private fun restartShippingApp(target: String) {
        device.executeShellCommand("am force-stop $target")
        val launch = requireNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(target))
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.context.startActivity(launch)
    }

    @Test
    fun searchProviderCreationSurvivesProcessRestart() {
        val target = shippingTarget()
        val marker = "release-${UUID.randomUUID()}"
        val url = "https://$marker.invalid"
        var created = false
        var failure: Throwable? = null
        try {
            openSearchSettings(target)
            click(By.desc("Add Provider"))
            click(By.text("SearXNG"))
            click(By.text("Confirm"))
            assertTrue("Creation dialog did not close", device.wait(Until.gone(By.text("Confirm")), 10_000))
            created = true
            openFirstSearXng()
            // API URL is the first editor in the existing SearXNG form. No search is submitted.
            val editor = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
            editor.text = url
            assertTrue(device.wait(Until.hasObject(By.text(url)), 10_000))
            device.pressBack() // Close the keyboard before leaving the detail page.
            if (device.hasObject(By.desc("Delete"))) device.pressBack()
            await(By.desc("Add Provider"))
            openFirstSearXng()
            assertEquals(url, await(By.clazz("android.widget.EditText")).text)

            device.executeShellCommand("am force-stop $target")
            openSearchSettings(target)
            openFirstSearXng()
            assertEquals("Persisted search URL changed after process restart", url,
                await(By.clazz("android.widget.EditText")).text)
        } catch (error: Throwable) {
            failure = error
            captureFailure(marker, error)
            throw error
        } finally {
            if (created) {
                try {
                    // A matching URL proves ownership; never delete an arbitrary SearXNG item.
                    if (!device.hasObject(By.text(url))) {
                        openSearchSettings(target)
                        openFirstSearXng()
                    }
                    check(await(By.clazz("android.widget.EditText")).text == url) {
                        "Cannot prove ownership of the search item; leaving it for inspection ($marker)"
                    }
                    click(By.desc("Delete"))
                    await(By.desc("Add Provider"))
                } catch (cleanup: Throwable) {
                    captureFailure("$marker-cleanup", cleanup)
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    @Test
    fun skillZipImportsPreserveYamlAndRejectInvalidFrontmatter() {
        val target = shippingTarget()
        val marker = requireNotNull(InstrumentationRegistry.getArguments().getString("skillMarker"))
        require(marker.matches(Regex("[a-f0-9]{8}"))) { "Expected the staged fixture's eight-digit marker" }
        val folder = "PilotRelease-$marker"
        val names = listOf("release-normal-$marker", "release-nested-$marker")
        val owned = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            openSkills(target)
            names.forEach { check(!device.hasObject(By.text(it))) { "Fixture already exists: $it" } }
            listOf("normal", "nested").forEachIndexed { index, kind ->
                // The exact names were absent before import; cleanup may only touch these imports.
                owned += names[index]
                importSkillZip(folder, "$kind.zip")
                await(By.text(names[index]))
                verifySkillSource(names[index], "Release $kind body $marker", kind == "nested")
            }
            importSkillZip(folder, "invalid.zip")
            val diagnostic = await(By.textContains("SKILL.md:")).text
            assertTrue("Parser location missing", diagnostic.contains("line") && diagnostic.contains("column"))
            assertTrue("Parser cause missing", diagnostic.contains("ParserException"))
            click(By.desc("Close"))
            check(!device.hasObject(By.text("release-invalid-$marker"))) { "Invalid YAML became a Skill" }

            device.executeShellCommand("am force-stop $target")
            openSkills(target)
            check(!device.hasObject(By.text("release-invalid-$marker"))) { "Invalid YAML persisted after restart" }
            names.forEachIndexed { index, name ->
                val kind = if (index == 0) "normal" else "nested"
                verifySkillSource(name, "Release $kind body $marker", index == 1)
            }
        } catch (error: Throwable) {
            failure = error
            captureFailure("skill-$marker", error)
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            owned.forEach { name ->
                try {
                    openSkills(target)
                    val label = device.findObject(By.text(name)) ?: return@forEach
                    val card = clickableAncestor(label)
                    requireNotNull(card.findObject(By.desc("More actions"))).click()
                    click(By.text("Delete"))
                    await(By.text("Delete Skill"))
                    await(By.text("Are you sure you want to delete $name? This action cannot be undone."))
                    click(By.text("Delete"))
                    assertTrue("Owned Skill was not deleted: $name", device.wait(Until.gone(By.text(name)), 20_000))
                } catch (cleanup: Throwable) {
                    captureFailure("skill-$marker-cleanup-$name", cleanup)
                    if (cleanupFailure == null) cleanupFailure = cleanup else cleanupFailure.addSuppressed(cleanup)
                }
            }
            cleanupFailure?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }

    private fun importSkillZip(folder: String, file: String) {
        click(By.desc("Add Skill"))
        click(By.text("Import from File"))
        click(By.desc("Show roots"))
        await(By.text("Open from"))
        click(By.text("Downloads").res("android:id/title"))
        click(By.text(folder))
        click(By.text(file))
    }

    private fun verifySkillSource(name: String, body: String, nested: Boolean) {
        val card = clickableAncestor(await(By.text(name)))
        val description = if (nested) "First release line\nSecond release line" else "Release scalar YAML verification"
        assertTrue("Parsed description missing from Skill card: $name", card.hasObject(By.text(description)))
        card.click()
        await(By.text("SKILL.md"))
        click(By.desc("Edit"))
        val content = await(By.clazz("android.widget.EditText")).text
        assertTrue("Original body missing", content.contains(body))
        assertTrue("Original name missing", content.contains(name))
        if (nested) {
            assertTrue(content.contains("First release line"))
            assertTrue(content.contains("Second release line"))
            assertTrue("Unknown nested metadata was lost", content.contains("metadata:"))
        } else {
            assertTrue(content.contains("Release scalar YAML verification"))
            assertTrue(content.contains("Android"))
        }
        click(By.text("Cancel"))
        device.pressBack()
        await(By.desc("Add Skill"))
    }

    private fun shippingTarget(): String {
        val target = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))
        require(target == "net.weero.measix.pilot") { "This test requires the shipping package" }
        val info = instrumentation.context.packageManager.getApplicationInfo(target, 0)
        require(info.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Target must be non-debuggable" }
        require(instrumentation.targetContext.packageName != target) { "Runner must instrument itself" }
        return target
    }

    private fun openSearchSettings(target: String) =
        openSettingsEntry(target, "Search Service", By.desc("Add Provider"))

    private fun openSkills(target: String) {
        openSettingsEntry(target, "Extensions", By.text("Agent Skills"))
        clickableAncestor(await(By.text("Agent Skills"))).click()
        await(By.desc("Add Skill"))
    }

    private fun openSettingsEntry(target: String, entry: String, ready: BySelector) {
        val launch = requireNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(target))
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.context.startActivity(launch)
        click(By.desc("Open conversation navigation"))
        click(By.desc("Settings"))
        repeat(8) {
            device.waitForIdle()
            val label = device.findObject(By.text(entry))
            if (label != null) {
                val row = clickableAncestor(label)
                val bounds = row.visibleBounds
                val safeTop = device.displayHeight / 4
                val safeBottom = device.displayHeight * 7 / 10
                if (bounds.top >= safeTop && bounds.bottom <= safeBottom) {
                    // Reacquire after idle: a label discovered during a scroll is not a tap target yet.
                    device.waitForIdle()
                    val settled = clickableAncestor(await(By.text(entry)))
                    if (settled.visibleBounds == bounds) {
                        settled.click()
                        await(ready)
                        return
                    }
                } else {
                    val start = device.displayHeight * 3 / 4
                    val shift = bounds.centerY() - device.displayHeight / 2
                    val end = (start - shift).coerceIn(device.displayHeight / 4, device.displayHeight * 9 / 10)
                    device.swipe(device.displayWidth / 2, start, device.displayWidth / 2, end, 24)
                }
            } else {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 3, 24)
            }
        }
        error("$entry was not found in Settings")
    }

    private fun clickableAncestor(label: UiObject2): UiObject2 {
        var node = label
        while (!node.isClickable) node = requireNotNull(node.parent) { "No clickable owner for ${label.text}" }
        return node
    }

    private fun openFirstSearXng() {
        await(By.text("SearXNG"))
        // The production add command prepends its new item; existing items remain untouched.
        requireNotNull(device.findObjects(By.text("SearXNG")).minByOrNull { it.visibleBounds.top }).click()
        await(By.text("Configuration"))
        await(By.desc("Delete"))
    }

    private fun await(selector: BySelector): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), 20_000)) { "Missing UI: $selector" }

    private fun click(selector: BySelector) {
        repeat(3) { attempt ->
            device.waitForIdle()
            try {
                val bounds = await(selector).visibleBounds
                android.os.SystemClock.sleep(200)
                val settled = await(selector)
                if (settled.visibleBounds != bounds) {
                    check(attempt < 2) { "UI target is still moving: $selector" }
                    return@repeat
                }
                clickableAncestor(settled).click()
            } catch (stale: StaleObjectException) {
                // DocumentsUI replaces drawer nodes during its opening animation; reacquire only
                // when UiObject2 could not access its node, not after a missing destination.
                if (attempt == 2) throw stale
                return@repeat
            }
            device.waitForIdle()
            return
        }
    }

    private fun captureFailure(marker: String, error: Throwable) {
        try {
            val folder = File(instrumentation.context.getExternalFilesDir(null), "release-consumers").apply { mkdirs() }
            device.takeScreenshot(File(folder, "$marker.png"))
            device.dumpWindowHierarchy(File(folder, "$marker.xml"))
            File(folder, "$marker.txt").writeText(error.stackTraceToString())
            Log.e("ReleaseConsumers", "Failure evidence: ${folder.absolutePath}/$marker", error)
        } catch (capture: Throwable) {
            error.addSuppressed(capture)
        }
    }
}
