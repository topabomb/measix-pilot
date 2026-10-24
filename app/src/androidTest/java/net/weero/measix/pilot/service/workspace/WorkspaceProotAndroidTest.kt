package net.weero.measix.pilot.service.workspace

import android.content.Context
import android.graphics.Typeface
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import me.rerere.workspace.ProotLaunchSpec
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsCompatibility
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.readResult
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

/** Opt-in matching-ABI Linux fixture; the ordinary PTY tests use Android's system shell. */
@RunWith(AndroidJUnit4::class)
class WorkspaceProotAndroidTest {
    @Before
    fun awaitStartupRecovery(): Unit = runBlocking {
        // Recovery owns Workspace temp cleanup; installing before it finishes races staging deletion.
        withTimeout(60_000) {
            org.koin.core.context.GlobalContext.get()
                .get<net.weero.measix.pilot.service.ApplicationRecoveryGate>().awaitReady()
        }
    }

    /** Explicit 26.04 fixture: exercise the package manager and Rust coreutils, not just --version. */
    @Test
    fun ubuntu2604InstallsReplacesAndPurgesLocalPackageUsingRustCoreutils(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable prootUbuntu2604 with the matching official Ubuntu 26.04 fixture",
            args.getString("prootUbuntu2604") == "true")
        val url = requireNotNull(args.getString("prootRootfsUrl"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val native = File(context.applicationInfo.nativeLibraryDir)
        val compatibility = RootfsCompatibility(native)
        assumeTrue("The amd64 Ubuntu fixture requires a 4 KB host",
            compatibility.architecture != RootfsCompatibility.Architecture.X86_64 || Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L)
        val root = "proot-dpkg-${UUID.randomUUID()}"
        val manager = WorkspaceManager(File(context.filesDir, "workspaces"), shellRunner = ProotShellRunner(native),
            bindMounts = ProotLaunchSpec.appBindMounts(context.filesDir))
        var stage = "install Ubuntu fixture"
        try {
            withContext(Dispatchers.IO) { RootfsInstaller(manager, native).install(root, url) }
            suspend fun shell(command: String): String = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, command, timeoutMillis = 120_000).also {
                    assertFalse("$stage timed out: ${it.stderr}", it.timedOut)
                    assertEquals("$stage: ${it.stderr}", 0, it.exitCode)
                }.stdout.trim()
            }
            stage = "verify actual distribution and Rust utility"
            assertTrue(shell("cat /etc/os-release").contains("VERSION_ID=\"26.04\""))
            for (utility in listOf("ls", "cp", "rm", "ln")) {
                assertTrue("$utility must really use Rust coreutils",
                    shell("/usr/bin/coreutils $utility --version").contains("uutils", ignoreCase = true))
            }
            val files = manager.filesDir(root)
            for (version in listOf("1.0", "2.0")) {
                stage = "prepare offline package $version"
                withContext(Dispatchers.IO) {
                    val control = File(files, "pkg/DEBIAN").apply { check(isDirectory || mkdirs()) }
                    File(control, "control").writeText("Package: pilot-proot-acceptance\nVersion: $version\nSection: misc\nPriority: optional\nArchitecture: all\nMaintainer: Pilot Test <test@localhost>\nDescription: Isolated PRoot acceptance fixture\n")
                    File(control, "postinst").apply {
                        // Ubuntu's default cp/rm can still select GNU; explicitly exercise its shipped Rust binary.
                        writeText("#!/bin/sh\nset -eu\n/usr/bin/coreutils cp /usr/share/pilot-proot-acceptance/payload /workspace/installed-copy\n/usr/bin/coreutils rm -f /workspace/installed-link\n/usr/bin/coreutils ln /workspace/installed-copy /workspace/installed-link\ncmp /workspace/installed-copy /workspace/installed-link\n")
                        check(setExecutable(true))
                    }
                    File(files, "pkg/usr/share/pilot-proot-acceptance").apply { check(isDirectory || mkdirs()) }
                    File(files, "pkg/usr/share/pilot-proot-acceptance/payload").writeText("payload-$version\n")
                    File(files, "pkg").walkTopDown().forEach { entry ->
                        Os.chmod(entry.absolutePath, if (entry.isDirectory || entry.name == "postinst") 493 else 420)
                    }
                }
                stage = "build and install package $version"
                shell("set -e; dpkg-deb --root-owner-group --build pkg package.deb; dpkg --install package.deb")
                assertEquals("payload-$version", shell("cat /usr/share/pilot-proot-acceptance/payload"))
                assertEquals("payload-$version", shell("cat /workspace/installed-copy; cmp /workspace/installed-copy /workspace/installed-link"))
                assertTrue(shell("dpkg-query --show pilot-proot-acceptance").endsWith(version))
            }
            stage = "purge installed package"
            shell("set -e; dpkg --purge pilot-proot-acceptance; test ! -e /usr/share/pilot-proot-acceptance/payload")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw AssertionError("Ubuntu 26.04 acceptance failed at $stage", error)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                assertTrue("Temporary Ubuntu workspace cleanup failed: $root", manager.deleteWorkspace(root))
            }
        }
    }

    /** Independent opt-in: missing probes appear as skipped, never as a passed subset of the PTY test. */
    @Test
    fun matchingNativeProbeChecksL2sExecutableNamesDescriptorsAndChildExecution() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val rootfsUrl = args.getString("prootRootfsUrl")
        val probeUrl = args.getString("prootBehaviorProbeUrl")
        assumeTrue("Supply prootRootfsUrl and a matching-ABI prootBehaviorProbeUrl", !rootfsUrl.isNullOrBlank() && !probeUrl.isNullOrBlank())
        assumeTrue("The static posix_spawn fixture requires Android API 28+", android.os.Build.VERSION.SDK_INT >= 28)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val native = File(context.applicationInfo.nativeLibraryDir)
        val compatibility = RootfsCompatibility(native)
        assumeTrue("The standard amd64 Ubuntu fixture requires a 4 KB host",
            compatibility.architecture != RootfsCompatibility.Architecture.X86_64 || Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L)
        val root = "proot-behavior-${UUID.randomUUID()}"
        val mounts = ProotLaunchSpec.appBindMounts(context.filesDir)
        val manager = WorkspaceManager(File(context.filesDir, "workspaces"), shellRunner = ProotShellRunner(native), bindMounts = mounts)
        var stage = "install"
        try {
            withContext(Dispatchers.IO) { RootfsInstaller(manager, native).install(root, requireNotNull(rootfsUrl)) }
            stage = "download matching static probe"
            withContext(Dispatchers.IO) {
                val connection = java.net.URL(requireNotNull(probeUrl)).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                }
                val bytes = connection.getInputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        check(output.size() + size <= 2 * 1024 * 1024) { "Native behavior probe exceeds 2 MiB" }
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                check(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                    bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[4] == 2.toByte() && bytes[5] == 1.toByte()) {
                    "Native behavior probe must be a little-endian ELF64 executable"
                }
                val machine = (bytes[18].toInt() and 255) or ((bytes[19].toInt() and 255) shl 8)
                val expected = if (compatibility.architecture == RootfsCompatibility.Architecture.X86_64) 62 else 183
                check(machine == expected) { "Behavior probe ABI mismatch: $machine, expected $expected" }
                File(manager.filesDir(root), "behavior-probe").apply { writeBytes(bytes); check(setExecutable(true)) }
            }
            suspend fun shell(command: String): String = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, command, timeoutMillis = 20_000).also {
                    assertFalse("$stage timed out: ${it.stderr}", it.timedOut)
                    assertEquals("$stage: ${it.stderr}", 0, it.exitCode)
                }.stdout.trim()
            }
            stage = "create hard links inside PRoot"
            shell("set -e; mkdir -p p36/bin p36/lib; cp behavior-probe p36/lib/original; ln p36/lib/original p36/lib/link; ln -s ../lib/link p36/bin/symlink; printf content > p36/data; ln p36/data p36/data-link")
            assertTrue("The link must be PRoot's emulated hard link, not a host hard link",
                java.nio.file.Files.isSymbolicLink(File(manager.filesDir(root), "p36/lib/link").toPath()))
            val linked = "/workspace/p36/lib/link"
            val symlink = "/workspace/p36/bin/symlink"
            val original = "/workspace/p36/lib/original"
            stage = "direct initial executable lookup"
            for (path in listOf(linked, symlink, original)) {
                val result = runInterruptible(Dispatchers.IO) {
                    val environment = compatibility.requireCompatible(manager.linuxDir(root))
                    val spec = ProotLaunchSpec.from(native, manager.filesDir(root), manager.linuxDir(root),
                        manager.tempDir(root), mounts, environment)
                    val commandOffset = spec.arguments.indexOf(environment.envCommand.first())
                    check(commandOffset > 0)
                    // Retain the production flags/binds/cwd; make the fixture the initial guest instead of env/sh.
                    ProcessBuilder(listOf(spec.executable.absolutePath) + spec.arguments.take(commandOffset) + listOf(path, "self"))
                        .directory(spec.workingDirectory).apply { environment().putAll(spec.environment) }
                        .start().readResult(20_000)
                }
                assertFalse("$stage: ${result.stderr}", result.timedOut)
                assertEquals("$stage: ${result.stderr}", 0, result.exitCode)
                assertEquals(if (path == original) original else linked, result.stdout.trim())
            }
            stage = "shell exec and execveat naming"
            for (path in listOf(linked, symlink, original)) {
                assertEquals(if (path == original) original else linked, shell("$path self"))
            }
            assertEquals(linked, shell("/workspace/behavior-probe execat $symlink"))
            stage = "O_NOFOLLOW and O_PATH descriptor naming"
            for (mode in listOf("nofollow", "path")) {
                assertEquals("/workspace/p36/data-link", shell("/workspace/behavior-probe $mode /workspace/p36/data-link"))
            }
            stage = "regular and memfd descriptor access modes"
            val nativeProbeUrl = requireNotNull(args.getString("prootNativeProbeUrl")) {
                "Full descriptor acceptance requires the matching dynamic PIE prootNativeProbeUrl"
            }
            val nativeFixture = File(manager.filesDir(root), "native-probe")
            withContext(Dispatchers.IO) {
                val connection = java.net.URL(nativeProbeUrl).openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                }
                connection.getInputStream().use { input ->
                    nativeFixture.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 2 * 1024 * 1024) { "Native probe exceeds 2 MiB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val nativeResult = runInterruptible(Dispatchers.IO) {
                ProcessBuilder("/system/bin/linker64", nativeFixture.absolutePath, "reopen",
                    File(manager.filesDir(root), "native-reopen-file").absolutePath).start().readResult(20_000)
            }
            val reopened = "regular-reopen-ok\nregular-memfd-reopen-ok"
            val denied = "regular-reopen-ok\nmemfd-readonly-denied:EACCES"
            assertFalse("Native baseline timed out: $nativeResult", nativeResult.timedOut)
            assertTrue("Unexpected native descriptor result: $nativeResult",
                nativeResult.exitCode == 0 && nativeResult.stdout.trim() == reopened ||
                    nativeResult.exitCode == 42 && nativeResult.stdout.trim() == denied)
            val guestResult = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, "/workspace/behavior-probe reopen /workspace/p36/reopen-file", timeoutMillis = 20_000)
            }
            assertFalse("PRoot descriptor timeout: $guestResult", guestResult.timedOut)
            assertEquals("Native=$nativeResult; PRoot=$guestResult", nativeResult.exitCode, guestResult.exitCode)
            assertEquals(nativeResult.stdout.trim(), guestResult.stdout.trim())
            android.util.Log.i("ProotDescriptorPolicy", "native=$nativeResult; proot=$guestResult")
            stage = "creat new file and truncation"
            assertEquals("creat-ok", shell("/workspace/behavior-probe create /workspace/p36/created-file"))
            stage = "vfork and posix_spawn child lifecycle"
            assertEquals("vfork-posix-spawn-ok", shell("/workspace/behavior-probe spawn $original"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw AssertionError("PRoot behavior acceptance failed at $stage", error)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { manager.deleteWorkspace(root) }
        }
    }

    @Test
    fun matchingRootfsRunsProductionShellAndTwoPtysWithCancellationCleanup() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("prootRootfsUrl")
        assumeTrue("Supply prootRootfsUrl for a matching-ABI Rootfs acceptance run", !url.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = "proot-test-${UUID.randomUUID()}"
        val native = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("The standard amd64 Ubuntu fixture requires a 4 KB host; the 16 KB rejection has a separate test",
            RootfsCompatibility(native).architecture != RootfsCompatibility.Architecture.X86_64 ||
                Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L)
        val manager = WorkspaceManager(
            File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(native),
            bindMounts = ProotLaunchSpec.appBindMounts(context.filesDir),
        )
        val stateDir = File(context.cacheDir, root).apply { check(mkdirs()) }
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(stateDir, "enterprise")), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}"))).also { it.recover() }
        val scope = AppScope()
        val runtime = WorkspaceTerminalRuntime(context, scope, sessions)
        var stage = "install"
        try {
            withContext(Dispatchers.IO) { RootfsInstaller(manager, native).install(root, requireNotNull(url)) }
            stage = "guest absolute bin link"
            withContext(Dispatchers.IO) {
                val environment = RootfsCompatibility(native).requireCompatible(manager.linuxDir(root))
                val bin = File(manager.linuxDir(root), "bin").toPath()
                if (environment.distribution?.contains("Alpine") == true) {
                    assertEquals("/bin/sh", environment.shell)
                    assertFalse(File(manager.linuxDir(root), "bin/bash").exists())
                    assertTrue(java.nio.file.Files.isSymbolicLink(File(manager.linuxDir(root), "bin/sh").toPath()))
                } else {
                    assertTrue("The Ubuntu fixture must provide a bin symlink", java.nio.file.Files.isSymbolicLink(bin))
                    java.nio.file.Files.delete(bin)
                    java.nio.file.Files.createSymbolicLink(bin, File("/usr/bin").toPath())
                    assertEquals("/bin/bash", environment.shell)
                }
                assertTrue(manager.hasRootfsFiles(root))
                assertTrue(RootfsCompatibility(native).isCompatible(manager.linuxDir(root)))
            }
            assertTrue(workspaceRootfsReady(context, root))
            InstrumentationRegistry.getArguments().getString("prootForkProbeUrl")?.let { probeUrl ->
                stage = "raw fork register preservation"
                assertEquals(RootfsCompatibility.Architecture.X86_64, RootfsCompatibility(native).architecture)
                withContext(Dispatchers.IO) {
                    val probe = File(manager.filesDir(root), "fork-registers")
                    val bytes = java.net.URL(probeUrl).openStream().use { it.readBytes() }
                    check(bytes.size in 1..65536) { "Invalid raw fork fixture size" }
                    probe.writeBytes(bytes)
                    check(probe.setExecutable(true))
                }
                val probe = runInterruptible(Dispatchers.IO) {
                    manager.executeCommand(root, "set -e; for i in \$(seq 1 32); do /workspace/fork-registers; done", timeoutMillis = 10_000)
                }
                assertEquals(probe.stderr, 0, probe.exitCode)
                assertEquals(List(32) { "fork-registers-ok" }, probe.stdout.trim().lines())
            }
            stage = "shell file operations and output"
            val result = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, "set -e; test \"\$PWD\" = /workspace; mkdir probe; printf CONTENT > probe/a; mv probe/a probe/b; test \"\$(stat -c %s probe/b)\" = 7; cat probe/b; printf '\\n'; uname -r; for i in \$(seq 1 1000); do echo LINE_\$i; done")
            }
            assertEquals(result.stderr, 0, result.exitCode)
            assertTrue(result.stdout.contains("CONTENT\n4.14.0\n"))
            assertTrue(result.stdout.contains("LINE_1000"))
            val piped = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, "cat; printf STDERR >&2", stdin = "STDIN".toByteArray())
            }
            assertEquals(0, piped.exitCode)
            assertEquals("STDIN", piped.stdout)
            assertEquals("STDERR", piped.stderr)
            stage = "command timeout"
            val timeoutStarted = SystemClock.elapsedRealtime()
            val timeout = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, "sleep 30 & echo \$! > timeout-child; wait", timeoutMillis = 1_000)
            }
            assertTrue(timeout.timedOut)
            assertTrue("Timeout must terminate and reap without waiting for sleep", SystemClock.elapsedRealtime() - timeoutStarted < 5_000)
            assertProcessGone(File(manager.filesDir(root), "timeout-child"))
            stage = "command cancellation marker"
            val cancelled = launch(Dispatchers.IO) {
                runInterruptible { manager.executeCommand(root, "sleep 30 & echo \$! > cancel-child; touch cancel-started; wait") }
            }
            withTimeout(10_000) {
                while (!File(manager.filesDir(root), "cancel-started").exists()) delay(20)
                stage = "command cancellation join"
                cancelled.cancelAndJoin()
            }
            assertTrue(cancelled.isCancelled)
            assertProcessGone(File(manager.filesDir(root), "cancel-child"))

            stage = "cancellation during process startup"
            for (cancelDelay in listOf(0L, 2L, 10L, 50L)) {
                val pidFile = File(manager.filesDir(root), "early-$cancelDelay-child")
                val starting = launch(Dispatchers.IO) {
                    runInterruptible {
                        manager.executeCommand(root, "sleep 30 & echo \$! > ${pidFile.name}; wait")
                    }
                }
                delay(cancelDelay)
                withTimeout(5_000) { starting.cancelAndJoin() }
                if (pidFile.exists()) assertProcessGone(pidFile)
                assertFalse("A cancelled invocation must not retain its PRoot process", hasTracer(manager.linuxDir(root)))
            }

            stage = "cancellation while guest children are being forked"
            val childMarker = "proot-child-$root"
            val forking = launch(Dispatchers.IO) {
                runInterruptible {
                    manager.executeCommand(root, "for i in \$(seq 1 64); do (exec -a $childMarker sleep 30) & touch fork-started; done; wait")
                }
            }
            withTimeout(10_000) {
                while (!File(manager.filesDir(root), "fork-started").exists()) delay(2)
                forking.cancelAndJoin()
            }
            assertFalse("Late child registration must not escape cancellation", processArguments().any { childMarker in it })
            assertFalse(hasTracer(manager.linuxDir(root)))

            val selection = requireNotNull(sessions.readPresentation().selection)
            val tabs = mutableListOf<String>()
            for (label in listOf("ONE", "TWO")) {
                stage = "PTY $label"
                val created = runtime.create(root, selection) { it() } as WorkspaceTerminalCreateResult.Created
                tabs += created.tabId
                await { runtime.workspaces.value.values.flatMap { it.tabs }.any { it.id == created.tabId && it.readiness == WorkspaceTerminalReadiness.READY } }
                val view = withContext(Dispatchers.Main) {
                    TerminalView(context, null).apply {
                        setTerminalViewClient(WorkspaceTerminalViewClient(context))
                        setTextSize(18)
                        setTypeface(Typeface.MONOSPACE)
                    }
                }
                assertTrue(runtime.bind(selection, created.tabId, view))
                withContext(Dispatchers.Main) {
                    view.layout(0, 0, 800, 600)
                    runtime.write(selection, created.tabId, "stty -echo; sleep 30 & echo \$! > pty-$label-child; test \"\$PWD\" = /workspace && printf 'PTY_%s_OK\\n' $label\n")
                }
                await { view.currentSession?.emulator?.screen?.transcriptText?.contains("PTY_${label}_OK") == true }
            }
            stage = "close first PTY"
            withTimeout(10_000) { runtime.close(root, selection, tabs.first()) }
            assertProcessGone(File(manager.filesDir(root), "pty-ONE-child"))
            assertEquals(listOf(tabs.last()), runtime.workspaces.value.values.flatMap { it.tabs }.map { it.id })
            Os.kill(File(manager.filesDir(root), "pty-TWO-child").readText().trim().toInt(), 0)
            stage = "close remaining PTY"
            withTimeout(10_000) { runtime.closeWorkspace(root) }
            assertProcessGone(File(manager.filesDir(root), "pty-TWO-child"))
            assertTrue(runtime.workspaces.value.values.all { it.tabs.isEmpty() })
        } catch (error: Throwable) {
            throw AssertionError("PRoot acceptance failed at $stage", error)
        } finally {
            withContext(NonCancellable) { runtime.closeWorkspace(root) }
            scope.cancel()
            withContext(Dispatchers.IO) {
                manager.deleteWorkspace(root)
                stateDir.deleteRecursively()
            }
        }
    }

    @Test
    fun sixteenKPageHostRejectsFourKGuestBeforeReplacingExistingFiles() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("prootRootfsUrl")
        assumeTrue("Supply prootRootfsUrl for a matching-ABI Rootfs acceptance run", !url.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val native = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("This rejection fixture is for x86_64 16 KB hosts",
            RootfsCompatibility(native).architecture == RootfsCompatibility.Architecture.X86_64 &&
                Os.sysconf(OsConstants._SC_PAGESIZE) == 16384L)
        val root = "proot-page-test-${UUID.randomUUID()}"
        val manager = WorkspaceManager(File(context.filesDir, "workspaces"))
        manager.ensureWorkspace(root)
        val preserved = File(manager.linuxDir(root), "preserved").apply { writeText("original") }
        try {
            withContext(Dispatchers.IO) {
                try {
                    RootfsInstaller(manager, native).install(root, requireNotNull(url))
                    fail("A 4 KB guest must not be installed on this 16 KB host")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message, expected.message.orEmpty().contains("16384"))
                }
                assertEquals("original", preserved.readText())
                assertFalse(File(manager.linuxDir(root), "usr/bin/env").exists())
            }
        } finally { withContext(Dispatchers.IO) { manager.deleteWorkspace(root) } }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(15_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(20)
    }

    private suspend fun assertProcessGone(pidFile: File) = withTimeout(5_000) {
        val pid = pidFile.readText().trim().toInt()
        while (true) {
            try {
                Os.kill(pid, 0)
            } catch (error: ErrnoException) {
                assertEquals(OsConstants.ESRCH, error.errno)
                return@withTimeout
            }
            val status = try {
                File("/proc/$pid/status").readText()
            } catch (error: java.io.IOException) {
                try {
                    Os.kill(pid, 0)
                } catch (gone: ErrnoException) {
                    assertEquals(OsConstants.ESRCH, gone.errno)
                    return@withTimeout
                }
                throw error
            }
            val fields = status.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator < 0) null else line.substring(0, separator) to line.substring(separator + 1).trim()
            }.toMap()
            // A tracer can finish reaping before init removes an orphaned zombie/dead PID.
            // Any running, stopped, or still-traced child is an immediate ownership failure.
            assertTrue("Guest process $pid survived its owner: $status", fields["State"]?.substringBefore(' ') in setOf("Z", "X"))
            assertEquals(status, "1", fields["PPid"])
            assertEquals(status, "0", fields["TracerPid"])
            delay(10)
        }
    }

    private fun hasTracer(linuxDir: File): Boolean = processArguments().any { arguments ->
        arguments.firstOrNull()?.endsWith("/libproot_exec.so") == true && linuxDir.absolutePath in arguments
    }

    private fun processArguments(): List<List<String>> = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
        if (entry.name.toIntOrNull() == null) return@mapNotNull null
        try { File(entry, "cmdline").readText().split('\u0000') }
        catch (_: java.io.IOException) { null }
    }
}
