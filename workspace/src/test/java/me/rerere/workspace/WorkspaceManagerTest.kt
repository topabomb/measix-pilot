package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceManagerTest {
    @Test
    fun journalPublicationFailureRetainsItsCauseAndStagedFiles() {
        val base = Files.createTempDirectory("workspace-journal-failure").toFile()
        try {
            val manager = WorkspaceManager(base)
            manager.ensureWorkspace("one")
            File(manager.filesDir("one"), "keep.txt").writeText("original")
            assertTrue(manager.stageWorkspaceDeletion("one"))
            val blockedJournal = File(base, ".workspace-delete-one.journal").apply { mkdir() }
            File(blockedJournal, "block").writeText("existing")

            val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                manager.writeWorkspaceDeletionJournal("one", "{}".encodeToByteArray())
            }

            assertTrue(error.toString(), error.message.orEmpty().contains("journal"))
            assertEquals(setOf(".workspace-delete-one", ".workspace-delete-one.journal"), base.list()!!.toSet())
            assertTrue(manager.restoreStagedWorkspaceDeletion("one"))
            assertEquals("original", File(manager.filesDir("one"), "keep.txt").readText())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun browserPathsUseGuestRootsWithoutLeakingHostDirectories() {
        val base = Files.createTempDirectory("workspace-paths").toFile()
        val manager = WorkspaceManager(base)
        manager.ensureWorkspace("one")
        manager.ensureWorkspace("two")
        File(manager.filesDir("one"), "report name").writeText("file")
        File(manager.linuxDir("one"), "etc").mkdirs()
        File(manager.linuxDir("one"), "etc/config").writeText("config")
        assertEquals(WorkspaceGuestPath.Available("/workspace/report name"),
            manager.guestPath("one", WorkspaceStorageArea.FILES, "report name"))
        assertEquals(WorkspaceGuestPath.Available("/etc/config"),
            manager.guestPath("one", WorkspaceStorageArea.LINUX, "etc/./config"))
        assertEquals(WorkspaceGuestPath.Available("/workspace"), manager.guestPath("one", WorkspaceStorageArea.FILES, ""))
        assertEquals(WorkspaceGuestPath.Available("/"), manager.guestPath("one", WorkspaceStorageArea.LINUX, ""))
        org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
            manager.guestPath("two", WorkspaceStorageArea.FILES, "report name")
        }
        listOf("../outside", "a/../../outside", "bad\\path", "bad\u0000path").forEach { path ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                manager.guestPath("one", WorkspaceStorageArea.FILES, path)
            }
        }
    }

    @Test
    fun browserPathsRejectMountPointsThatReferToDifferentFiles() {
        val base = Files.createTempDirectory("workspace-mount-paths").toFile()
        val skills = File(base, "shared-skills").apply { mkdirs() }
        val manager = WorkspaceManager(base, bindMounts = listOf(WorkspaceBindMount(skills, "/skills")))
        manager.ensureWorkspace("one")
        listOf("workspace", "skills", "upload", "dev", "proc", "sys").forEach { directory ->
            File(manager.linuxDir("one"), "$directory/file").apply { checkNotNull(parentFile).mkdirs(); writeText("hidden") }
            assertEquals(WorkspaceGuestPath.Unavailable(WorkspaceGuestPath.Reason.MOUNT_SHADOWED),
                manager.guestPath("one", WorkspaceStorageArea.LINUX, "$directory/file"))
        }
        File(manager.linuxDir("one"), "workspace-other").writeText("ordinary")
        assertEquals(WorkspaceGuestPath.Available("/workspace-other"),
            manager.guestPath("one", WorkspaceStorageArea.LINUX, "workspace-other"))
    }

    @Test
    fun rootfsFilesDistinguishMissingAndEmptyDirectoriesFromPresentContent() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        assertFalse(manager.hasRootfsFiles(root))
        manager.ensureWorkspace(root)
        assertFalse(manager.hasRootfsFiles(root))
        File(manager.linuxDir(root), "etc").mkdirs()
        assertTrue(manager.hasRootfsFiles(root))
    }

    @Test
    fun prootRunnerRequiresRootfs() {
        val baseDir = Files.createTempDirectory("workspace-proot-test").toFile()
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(File(baseDir, "native"))
        )
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "cat /etc/os-release")

        assertEquals(127, result.exitCode)
        assertEquals("Rootfs is not installed", result.stderr)
    }
}
