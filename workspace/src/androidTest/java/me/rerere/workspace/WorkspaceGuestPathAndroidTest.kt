package me.rerere.workspace

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class WorkspaceGuestPathAndroidTest {
    @Test
    fun preservesFileNamesAndDoesNotFollowSymbolicParentsOrShadowedMounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Files.createTempDirectory(context.cacheDir.toPath(), "guest-path-").toFile().canonicalFile
        try {
            val manager = WorkspaceManager(base)
            manager.ensureWorkspace("test")
            val files = manager.filesDir("test")
            val name = "\u6587\u4ef6 name "
            File(files, name).writeText("file")
            assertEquals(WorkspaceGuestPath.Available("/workspace/$name"),
                manager.guestPath("test", WorkspaceStorageArea.FILES, name))
            val folder = File(files, "folder").apply { mkdirs() }
            File(folder, "file").writeText("data")
            Os.symlink(folder.absolutePath, File(files, "link").absolutePath)
            assertEquals(WorkspaceGuestPath.Unavailable(WorkspaceGuestPath.Reason.SYMBOLIC_PARENT),
                manager.guestPath("test", WorkspaceStorageArea.FILES, "link/file"))
            // Copy the link itself without resolving its target or granting access to that target.
            assertEquals(WorkspaceGuestPath.Available("/workspace/link"),
                manager.guestPath("test", WorkspaceStorageArea.FILES, "link"))
            File(manager.linuxDir("test"), "workspace/file").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("shadowed")
            }
            assertEquals(WorkspaceGuestPath.Unavailable(WorkspaceGuestPath.Reason.MOUNT_SHADOWED),
                manager.guestPath("test", WorkspaceStorageArea.LINUX, "workspace/file"))
        } finally {
            // Remove the symlink explicitly before recursively removing this test-owned directory.
            File(base, "test/files/link").delete()
            base.deleteRecursively()
        }
    }
}
