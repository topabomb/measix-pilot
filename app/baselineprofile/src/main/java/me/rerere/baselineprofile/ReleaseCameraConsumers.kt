package me.rerere.baselineprofile

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

/** External UI consumer of the installed shipping APK; no application instrumentation or hooks. */
@RunWith(AndroidJUnit4::class)
class ReleaseCameraConsumers {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val packageManager = instrumentation.context.packageManager
    private val runId = UUID.randomUUID().toString()
    private val evidenceDirectory by lazy {
        File(instrumentation.context.getExternalFilesDir(null), "release-camera/$runId").also {
            check(it.isDirectory || it.mkdirs())
        }
    }

    @Test
    fun shippingScannerDeliversSelectedCameraFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        val target = requireNotNull(arguments.getString("targetAppId"))
        require(target == "net.weero.measix.pilot")
        require(instrumentation.targetContext.packageName != target)
        require(packageManager.getApplicationInfo(target, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val kind = requireNotNull(arguments.getString("cameraFixtureKind"))
        require(kind in setOf("provider", "mcp", "enterprise", "invalid-provider", "invalid-mcp", "invalid-enterprise"))
        val name = requireNotNull(arguments.getString("cameraFixtureName"))
        require(Regex("P96-QR-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(name)) {
            "Pass the unique P96-QR-UUID fixture identity; arbitrary existing definitions are not test-owned"
        }
        val entry = kind.removePrefix("invalid-")
        val invalid = kind.startsWith("invalid-")
        val originalPermission = cameraPermission(target)
        require(!originalPermission.granted && originalPermission.flags ==
            setOf("USER_SENSITIVE_WHEN_GRANTED", "USER_SENSITIVE_WHEN_DENIED")) { "Unexpected initial CAMERA state: $originalPermission" }
        var definitionMayBeOwned = false
        var failure: Throwable? = null
        try {
            when (entry) {
                "provider" -> {
                    openProviders(target)
                    filterProviders(name)
                    check(!device.hasObject(nameSelector(name))) { "Fixture provider already exists; refusing to own it" }
                    definitionMayBeOwned = true
                    openScanner()
                }
                "mcp" -> {
                    openMcp(target)
                    check(findMcpEntry(name) == null) { "Fixture MCP server already exists; refusing to own it" }
                    definitionMayBeOwned = true
                    openMcpScanner(target)
                }
                "enterprise" -> {
                    openEnterprise(target)
                    click(By.text("Scan to join").enabled(true))
                }
            }
            permissionButton("permission_allow_foreground_only_button").click()
            when (kind) {
                "provider" -> await(nameSelector(name))
                "mcp" -> {
                    // This is a four-second toast; capture it before slower resource inspection.
                    await(By.text("Imported 1 config(s)"))
                    evidence("$kind-import-receipt", target)
                }
                "enterprise" -> {
                    await(By.text("Join enterprise"))
                    await(By.textContains("https://p96-qr.invalid"))
                    check(device.findObjects(By.text("Join enterprise")).size == 1)
                    evidence("$kind-confirmation", target)
                    click(By.text("Cancel"))
                    assertTrue(device.wait(Until.gone(By.text("Join enterprise")), 20_000))
                }
                "invalid-provider" -> {
                    await(By.text("Import Provider Configuration"))
                    await(By.textContains("Invalid provider setting string"))
                    evidence("$kind-diagnostic", target)
                    click(By.text("Confirm"))
                }
                "invalid-mcp" -> {
                    await(By.textStartsWith("JSON parse failed:"))
                    evidence("$kind-diagnostic", target)
                }
                "invalid-enterprise" -> {
                    await(By.text(INVALID_ENROLLMENT))
                    assertFalse(device.hasObject(By.text("Join enterprise")))
                    click(By.text("Technical information"))
                    await(By.textContains("invalid_enterprise_enrollment"))
                    evidence("$kind-diagnostic", target)
                    click(By.desc("Close"))
                }
            }
            assertTrue("Camera permission was not granted through the system UI", cameraPermission(target).granted)
            assertTrue("Scanner did not dismiss after delivery", device.wait(Until.gone(By.text("Scan QR Code")), 20_000))
            awaitResources(target, active = false, phase = "$kind-delivered")
            when (entry) {
                "provider" -> {
                    filterProviders(name)
                    val matches = device.findObjects(nameSelector(name))
                    check(matches.size == (if (invalid) 0 else 1)) { "Unexpected provider delivery count: ${matches.size}" }
                    if (!invalid) requireNotNull(namedCard(name).findObject(By.text("Disabled"))) { "Fixture provider was not imported disabled" }
                }
                "mcp" -> {
                    assertFalse("A second delivery attempted to import the same name", device.hasObject(By.text("Config Conflict")))
                    val found = findMcpEntry(name)
                    check((found == null) == invalid) { "Unexpected MCP import presence for $kind" }
                }
                "enterprise" -> {
                    assertFalse("A late delivery reopened enterprise confirmation", device.hasObject(By.text("Join enterprise")))
                    // The fixture is never submitted: returning to this action proves no enrollment UI transition.
                    await(By.text("Scan to join"))
                }
            }
            evidence("$kind-result", target)
        } catch (error: Throwable) {
            failure = error
            evidence("$kind-failure", target, error)
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(operation: () -> Unit) {
                try { operation() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            cleanup {
                if (entry == "enterprise" && device.hasObject(By.text("Join enterprise"))) {
                    await(By.textContains("https://p96-qr.invalid"))
                    click(By.text("Cancel"))
                }
                if (device.hasObject(By.text("Scan QR Code")) && device.hasObject(By.desc("Cancel"))) click(By.desc("Cancel"))
                device.pressHome()
                awaitResources(target, active = false, phase = "$kind-cleanup-camera")
            }
            if (definitionMayBeOwned) cleanup {
                when (entry) {
                    "provider" -> {
                        openProviders(target)
                        filterProviders(name)
                        val matches = device.findObjects(nameSelector(name))
                        check(matches.size <= 1) { "Multiple fixture providers remain; inspect identities before deleting" }
                        if (matches.isNotEmpty()) {
                            click(nameSelector(name))
                            await(nameSelector(name))
                            openProviderDeleteAction()
                            await(By.text("Confirm Delete"))
                            click(By.text("Delete"))
                            await(By.desc("Add Provider"))
                            filterProviders(name)
                            check(!device.hasObject(nameSelector(name))) { "Owned provider remains after deletion" }
                        }
                    }
                    "mcp" -> {
                        openMcp(target)
                        if (findMcpEntry(name) != null) {
                            val card = namedCard(name)
                            val actions = card.findObjects(By.desc("More options"))
                            check(actions.size == 1) { "Cannot identify one menu inside the fixture MCP card" }
                            actions.single().click()
                            click(By.text("Delete"))
                            await(By.text("Confirm Delete"))
                            await(By.textContains(name))
                            click(By.text("Delete"))
                            assertTrue(device.wait(Until.gone(nameSelector(name)), 20_000))
                            check(findMcpEntry(name) == null) { "Owned MCP server remains after deletion" }
                        }
                    }
                }
            }
            cleanup {
                if (cameraPermission(target).granted) shell("pm revoke $target android.permission.CAMERA")
                shell("pm clear-permission-flags $target android.permission.CAMERA user-set user-fixed")
                check(cameraPermission(target) == originalPermission) { "CAMERA permission was not restored" }
            }
            cleanupFailure?.let { error ->
                evidence("$kind-cleanup-failure", target, error)
                if (failure == null) throw error else failure!!.addSuppressed(error)
            }
        }
    }

    @Test
    fun shippingProviderScannerOwnsCameraAcrossPermissionAndLifecycleChanges() {
        val target = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))
        require(target == "net.weero.measix.pilot")
        require(instrumentation.targetContext.packageName != target)
        require(packageManager.getApplicationInfo(target, 0).flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val originalPermission = cameraPermission(target)
        require(!originalPermission.granted) { "This scenario requires the recorded initially denied CAMERA permission" }
        require(originalPermission.flags == setOf("USER_SENSITIVE_WHEN_GRANTED", "USER_SENSITIVE_WHEN_DENIED")) {
            "Unexpected initial permission flags: ${originalPermission.flags}; do not overwrite an unknown permission decision"
        }
        val originalAutoRotation = shell("settings get system accelerometer_rotation").trim()
        val originalRotation = shell("settings get system user_rotation").trim()
        require(originalAutoRotation in setOf("0", "1") && originalRotation in setOf("0", "1", "2", "3"))
        var failure: Throwable? = null
        try {
            openProviders(target)
            openScanner()
            permissionButton("permission_deny_button").click()
            await(By.text(PERMISSION_EXPLANATION))
            await(By.text("Retry"))
            assertFalse(cameraPermission(target).granted)
            awaitResources(target, active = false, phase = "permission-denied")
            evidence("permission-denied", target)

            click(By.text("Retry"))
            permissionButton("permission_allow_foreground_only_button").click()
            await(By.text("Scan QR Code"))
            await(By.desc("Cancel"))
            assertTrue(cameraPermission(target).granted)
            awaitResources(target, active = true, phase = "permission-granted")
            evidence("permission-granted", target)

            closeScanner()
            awaitResources(target, active = false, phase = "closed")
            evidence("closed", target)

            openScanner()
            awaitResources(target, active = true, phase = "reopened")
            device.pressHome()
            awaitCondition("Home did not leave the shipping application") { device.currentPackageName != target }
            awaitResources(target, active = false, phase = "background")
            evidence("background", target)

            instrumentation.context.startActivity(requireNotNull(packageManager.getLaunchIntentForPackage(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            awaitCondition("Shipping application did not resume") { device.currentPackageName == target }
            resumeOrReopenScanner(target)
            awaitResources(target, active = true, phase = "foreground")
            evidence("foreground", target)

            device.setOrientationLeft()
            awaitCondition("Device did not rotate to landscape") { device.displayWidth > device.displayHeight }
            resumeOrReopenScanner(target)
            awaitResources(target, active = true, phase = "landscape")
            evidence("landscape", target)
            closeScanner()
            awaitResources(target, active = false, phase = "landscape-closed")
            evidence("landscape-closed", target)
        } catch (error: Throwable) {
            failure = error
            evidence("failure", target, error)
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(operation: () -> Unit) {
                try { operation() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
            }
            // Prove resource release before permission revocation can terminate the process.
            cleanup {
                if (device.hasObject(By.text("Scan QR Code")) && device.hasObject(By.desc("Cancel"))) {
                    click(By.desc("Cancel"))
                }
                device.pressHome()
                awaitResources(target, active = false, phase = "cleanup-before-revoke")
            }
            cleanup {
                if (cameraPermission(target).granted) shell("pm revoke $target android.permission.CAMERA")
                shell("pm clear-permission-flags $target android.permission.CAMERA user-set user-fixed")
                val restored = cameraPermission(target)
                check(restored == originalPermission) { "CAMERA restoration mismatch: expected $originalPermission; actual $restored" }
            }
            cleanup {
                device.unfreezeRotation()
                shell("settings put system user_rotation $originalRotation")
                shell("settings put system accelerometer_rotation $originalAutoRotation")
                check(shell("settings get system user_rotation").trim() == originalRotation)
                check(shell("settings get system accelerometer_rotation").trim() == originalAutoRotation)
            }
            cleanupFailure?.let { error ->
                evidence("cleanup-failure", target, error)
                if (failure == null) throw error else failure!!.addSuppressed(error)
            }
        }
    }

    private fun nameSelector(name: String): BySelector = By.clazz("android.widget.TextView").text(name)

    private fun filterProviders(name: String) {
        await(By.desc("Add Provider"))
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        check(fields.size == 1) { "Expected one provider search field, found ${fields.size}" }
        fields.single().text = name
        device.waitForIdle()
    }

    private fun namedCard(name: String): UiObject2 {
        val names = device.findObjects(nameSelector(name))
        check(names.size == 1) { "Expected one visible definition named $name, found ${names.size}" }
        var owner = names.single()
        while (!owner.isClickable) owner = requireNotNull(owner.parent)
        return owner
    }

    private fun openSettingsEntry(target: String, label: String) {
        instrumentation.context.startActivity(requireNotNull(packageManager.getLaunchIntentForPackage(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click(By.desc("Open conversation navigation"))
        click(By.desc("Settings"))
        findPageText(label)
        click(By.text(label))
    }

    private fun openMcp(target: String) {
        openSettingsEntry(target, "MCP")
        await(By.desc("Refresh MCP tools"))
    }

    private fun openEnterprise(target: String) {
        // A connected enterprise is not reset or replaced for scanner acceptance.
        openSettingsEntry(target, "Personal space")
        await(By.text("Scan to join"))
        check(await(By.text("Scan to join")).isEnabled) { "Enterprise scan entry is unavailable" }
    }

    private fun openMcpScanner(target: String) {
        evidence("mcp-import-actions", target)
        var refresh = await(By.desc("Refresh MCP tools"))
        while (!refresh.isClickable) refresh = requireNotNull(refresh.parent)
        val band = refresh.visibleBounds
        // Observed shipping hierarchy: Refresh, unlabeled Import, unlabeled Add in one LTR row.
        // Select actual accessibility objects; do not use fixture screen coordinates.
        val actions = device.findObjects(By.clazz("android.widget.Button").clickable(true)).filter {
            val bounds = it.visibleBounds
            bounds.centerY() in band.top..band.bottom && bounds.left >= band.right
        }.sortedBy { it.visibleBounds.left }
        check(actions.size == 2 && actions[0].visibleBounds.right <= actions[1].visibleBounds.left) {
            "Expected distinct Import/Add buttons right of Refresh; observed ${actions.map { it.visibleBounds }}"
        }
        actions.first().click()
        await(By.text("Import Provider Configuration"))
        click(By.text("Scan QR Code"))
    }

    private fun findMcpEntry(name: String): UiObject2? {
        await(By.desc("Refresh MCP tools"))
        return findTextAcrossPage(nameSelector(name))
    }

    private fun findPageText(text: String): UiObject2 =
        requireNotNull(findTextAcrossPage(By.text(text))) { "Page text is not present: $text" }

    private fun findTextAcrossPage(selector: BySelector): UiObject2? {
        repeat(3) { attempt ->
            try {
                return findTextAcrossPageOnce(selector)
            } catch (stale: androidx.test.uiautomator.StaleObjectException) {
                if (attempt == 2) throw stale
                SystemClock.sleep(200)
                device.waitForIdle()
            }
        }
        error("Page traversal did not complete: $selector")
    }

    private fun findTextAcrossPageOnce(selector: BySelector): UiObject2? {
        fun scrollable(): UiObject2? {
            val containers = device.findObjects(By.scrollable(true))
            check(containers.size <= 1) { "Ambiguous scroll containers while locating $selector" }
            return containers.singleOrNull()
        }
        var reachedStart = false
        repeat(30) {
            if (!reachedStart) {
                val container = scrollable()
                reachedStart = container == null || !container.scroll(Direction.UP, 1.0f)
                device.waitForIdle()
            }
        }
        check(reachedStart) { "Could not reach the beginning of the current list" }
        repeat(30) {
            val matches = device.findObjects(selector)
            check(matches.size <= 1) { "Ambiguous visible match: $selector" }
            if (matches.isNotEmpty()) return matches.single()
            val container = scrollable() ?: return null
            if (!container.scroll(Direction.DOWN, 1.0f)) {
                device.waitForIdle()
                val lastMatches = device.findObjects(selector)
                check(lastMatches.size <= 1) { "Ambiguous final visible match: $selector" }
                return lastMatches.singleOrNull()
            }
            device.waitForIdle()
        }
        error("Could not search the complete current list for $selector")
    }

    private fun openProviderDeleteAction() {
        // Same shipping footer identification already exercised by ReleaseRenderingConsumers.
        repeat(10) {
            device.waitForIdle()
            val save = device.findObject(By.text("Save"))
            if (save != null && save.visibleBounds.bottom < device.displayHeight * 9 / 10) {
                var reset = await(By.desc("Reset to default"))
                while (!reset.isClickable) reset = requireNotNull(reset.parent)
                val band = reset.visibleBounds
                val candidates = device.findObjects(By.clazz("android.widget.Button").clickable(true)).filter {
                    val bounds = it.visibleBounds
                    bounds.centerY() in band.top..band.bottom && bounds.right <= band.left && band.left - bounds.right <= bounds.width()
                }
                val nearestRight = candidates.maxOfOrNull { it.visibleBounds.right }
                val nearest = candidates.filter { it.visibleBounds.right == nearestRight }
                check(nearest.size == 1) { "Expected one Delete action immediately left of Reset" }
                nearest.single().click()
                device.waitForIdle()
                return
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 2, 24)
        }
        error("Owned provider's Save/Delete row is not visible")
    }

    private fun resumeOrReopenScanner(target: String) {
        awaitCondition("Resumed UI is neither scanner, provider list nor chat navigation") {
            device.hasObject(By.text("Scan QR Code")) || device.hasObject(By.desc("Add Provider")) ||
                device.hasObject(By.desc("Open conversation navigation"))
        }
        if (device.hasObject(By.text("Scan QR Code"))) {
            await(By.desc("Cancel"))
            return
        }
        // The entry uses remember, so a recreated page may legitimately close the scanner.
        awaitResources(target, active = false, phase = "recreated-entry")
        if (!device.hasObject(By.desc("Add Provider"))) openProviders(target)
        openScanner()
    }

    private fun openScanner() {
        clickUniqueButtonBeside(await(By.desc("Add Provider")))
        await(By.text("Import Provider Configuration"))
        click(By.text("Scan QR Code"))
    }

    private fun closeScanner() {
        await(By.text("Scan QR Code"))
        click(By.desc("Cancel"))
        await(By.desc("Add Provider"))
    }

    private fun permissionButton(id: String): UiObject2 {
        // IDs are from Android 16 PermissionController/res/layout/grant_permissions.xml.
        // Resolve the actual system package from the visible node rather than assuming the AOSP
        // or Google package name; never fall back to guessed screen coordinates or button text.
        val selector = By.res(Pattern.compile("[^:]+:id/${Pattern.quote(id)}"))
        val button = await(selector)
        check(device.findObjects(selector).size == 1) { "Permission action is ambiguous: $id" }
        val owner = requireNotNull(button.applicationPackage)
        check(packageManager.getApplicationInfo(owner, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0)
        check(packageManager.checkPermission("android.permission.GRANT_RUNTIME_PERMISSIONS", owner) == PackageManager.PERMISSION_GRANTED) {
            "Permission action does not belong to a runtime permission controller: $owner"
        }
        check(button.isClickable && button.isEnabled) { "Permission action is not actionable: $id" }
        return button
    }

    private data class PermissionState(val granted: Boolean, val flags: Set<String>)

    private fun cameraPermission(target: String): PermissionState {
        val dump = shell("dumpsys package $target")
        val matches = Regex("android\\.permission\\.CAMERA: granted=(true|false), flags=\\[([^]]*)]")
            .findAll(dump).toList()
        check(matches.size == 1) { "Cannot identify one CAMERA runtime permission record: ${matches.size}" }
        val match = matches.single()
        return PermissionState(match.groupValues[1].toBoolean(), match.groupValues[2].split('|')
            .map(String::trim).filter(String::isNotEmpty).toSet())
    }

    private data class Resources(val activeClients: String, val threads: String, val decoderCount: Int, val cameraActive: Boolean)

    private fun resources(target: String): Resources {
        val dump = shell("dumpsys media.camera")
        val start = dump.indexOf("Active Camera Clients:\n")
        check(start >= 0) { "Camera service did not expose its current client section" }
        val end = dump.indexOf("Allowed user IDs:", start)
        check(end > start) { "Camera service current-client section is unrecognized" }
        val clients = dump.substring(start + "Active Camera Clients:\n".length, end).trim()
        check(clients.startsWith('[') && clients.endsWith(']')) { "Malformed current camera clients: $clients" }
        val cameraActive = Regex("Client Package Name: ${Regex.escape(target)}(?=,|\\s|$)").containsMatchIn(clients)
        val pidText = shell("pidof $target", allowFailure = true).trim()
        val threads = if (pidText.isEmpty()) "process-not-running" else {
            val pids = pidText.split(Regex("\\s+"))
            check(pids.size == 1 && pids.single().all(Char::isDigit)) { "Unexpected shipping PID result: $pidText" }
            val pid = pids.single()
            shell("for task in /proc/$pid/task/[0-9]*; do " +
                "if [ ! -d \"\$task\" ]; then continue; fi; " +
                "name=\$(cat \"\$task/comm\" 2>&1); status=\$?; " +
                "if [ \"\$status\" -ne 0 ]; then " +
                "if [ ! -d \"\$task\" ]; then continue; fi; " +
                "echo THREAD_READ_DENIED \"\$task\" \"\$name\"; exit 1; fi; " +
                "printf '%s %s\\n' \"\${task##*/}\" \"\$name\"; done")
                .also { check(it.isNotBlank() && !it.contains("THREAD_READ_DENIED")) }
        }
        // Linux comm truncates the 16-character Java thread name to 15 characters.
        val decoderCount = threads.lineSequence().count { it.substringAfter(' ').trim() in setOf("qr-frame-decode", "qr-frame-decoder") }
        return Resources(clients, threads, decoderCount, cameraActive)
    }

    private fun awaitResources(target: String, active: Boolean, phase: String) {
        var last: Resources? = null
        var consecutive = 0
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            last = resources(target)
            val matched = last.cameraActive == active && last.decoderCount == (if (active) 1 else 0)
            consecutive = if (matched) consecutive + 1 else 0
            if (consecutive == 2) {
                File(evidenceDirectory, "$phase-resources.txt").writeText(last.toString())
                return
            }
            SystemClock.sleep(200)
        }
        error("Camera resources did not become ${if (active) "active with one decoder" else "fully released"} at $phase: $last")
    }

    private fun shell(command: String, allowFailure: Boolean = false): String {
        val marker = "PILOT_CAMERA_SHELL_STATUS:"
        // UiAutomation tokenizes arguments without shell quote parsing. Use an owned script
        // so pipelines and status checks execute under sh without interpolated command arguments.
        val script = File(evidenceDirectory, "shell-${UUID.randomUUID()}.sh")
        val output = try {
            script.writeText("( $command ) 2>&1; result=\$?; echo $marker\$result\n")
            device.executeShellCommand("sh ${script.absolutePath}")
        } finally {
            check(script.delete()) { "Cannot remove owned shell script: $script" }
        }
        val index = output.lastIndexOf(marker)
        check(index >= 0) { "Shell result status missing for $command: $output" }
        val status = output.substring(index + marker.length).trim().toInt()
        val result = output.substring(0, index).trimEnd()
        check(status == 0 || allowFailure && status == 1 && result.isBlank()) {
            "Shell failed ($status) for $command: $result"
        }
        return result
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error(message)
    }

    private fun openProviders(target: String) {
        instrumentation.context.startActivity(requireNotNull(packageManager.getLaunchIntentForPackage(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
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
            } else device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 2, 24)
        }
        error("Providers settings entry is not safely visible")
    }

    private fun clickUniqueButtonBeside(anchor: UiObject2) {
        device.waitForIdle()
        var owner = anchor
        while (!owner.isClickable) owner = requireNotNull(owner.parent)
        val band = owner.visibleBounds
        val buttons = device.findObjects(By.clazz("android.widget.Button").clickable(true)).filter {
            val bounds = it.visibleBounds
            bounds.centerY() in band.top..band.bottom && !bounds.contains(band.centerX(), band.centerY())
        }
        check(buttons.size == 1) { "Expected one import action beside Add Provider, found ${buttons.size}" }
        buttons.single().click()
        device.waitForIdle()
    }

    private fun await(selector: BySelector): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), 20_000)) { "Missing UI: $selector" }

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

    private fun evidence(name: String, target: String, error: Throwable? = null) {
        try {
            check(device.takeScreenshot(File(evidenceDirectory, "$name.png")))
            device.dumpWindowHierarchy(File(evidenceDirectory, "$name.xml"))
            File(evidenceDirectory, "$name-camera.txt").writeText(shell("dumpsys media.camera"))
            File(evidenceDirectory, "$name-permission.txt").writeText(cameraPermission(target).toString())
            error?.let { File(evidenceDirectory, "$name-error.txt").writeText(it.stackTraceToString()) }
            Log.i("ReleaseCamera", "Evidence: ${evidenceDirectory.absolutePath}/$name", error)
        } catch (capture: Throwable) {
            if (error != null) error.addSuppressed(capture) else throw capture
        }
    }

    private companion object {
        const val PERMISSION_EXPLANATION = "Allow camera access to scan a QR code. If permission is blocked, enable it in Settings."
        const val INVALID_ENROLLMENT = "This enrollment information is invalid. Scan or paste the complete enrollment information provided by your enterprise."
    }
}
