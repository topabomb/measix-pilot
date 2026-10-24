package net.weero.measix.pilot.data.files

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.SettingsLockedException

class SkillManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `unexpected staged IO failure preserves published bytes and can retry`() = runTest {
        val manager = manager()
        val original = document("stable", "original")
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", original))
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkillFile("stable", "blocking", "original support"))
        val error = runCatching { manager.saveSkillFile("stable", "blocking/child", "replacement") }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("blocking"))
        assertEquals(original, skillsRoot().resolve("stable/SKILL.md").readText())
        assertEquals("original support", skillsRoot().resolve("stable/blocking").readText())
        assertTrue(skillsRoot().listFiles().orEmpty().none { ".staging." in it.name })
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkillFile("stable", "valid/child", "retry"))
    }

    @Test
    fun `list exposes only skills accepted by the typed parser`() = runTest {
        val manager = manager()
        val root = skillsRoot()
        File(root, "valid").apply { mkdirs() }.resolve("SKILL.md").writeText(document("valid", "ok"))
        File(root, "invalid").apply { mkdirs() }.resolve("SKILL.md").writeText(
            "---\nname: invalid\ndescription: [not, text]\n---\nbody",
        )

        val listed = manager.listSkills()

        assertEquals(listOf("valid"), listed.map { it.name })
    }

    @Test
    fun `failed atomic save preserves the previously published skill`() = runTest {
        val manager = manager()
        val original = document("stable", "original")
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", original))

        val saved = manager.saveSkillFileBytesAtomically(
            "stable",
            mapOf(
                "SKILL.md" to document("stable", "replacement").toByteArray(),
                "../escape.txt" to "unsafe".toByteArray(),
            ),
        )

        assertEquals(SkillFileSaveResult.INVALID_PATH, saved)
        assertEquals(original, skillsRoot().resolve("stable/SKILL.md").readText())
        assertTrue(skillsRoot().listFiles().orEmpty().none { it.name.contains(".staging.") })
    }

    @Test
    fun `invalid or mismatched staged skill never replaces the published skill`() = runTest {
        val manager = manager()
        val original = document("stable", "original")
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", original))

        assertEquals(
            SkillFileSaveResult.INVALID_SKILL,
            manager.saveSkillFileBytesAtomically(
                "stable",
                mapOf("SKILL.md" to "---\nname: stable\ndescription: [invalid]\n---\nbody".toByteArray()),
            )
        )
        assertEquals(
            SkillFileSaveResult.NAME_MISMATCH,
            manager.saveSkillFileBytesAtomically(
                "stable",
                mapOf("SKILL.md" to document("different", "wrong name").toByteArray()),
            )
        )

        assertEquals(original, skillsRoot().resolve("stable/SKILL.md").readText())
    }

    @Test
    fun `valid replacement atomically swaps the published skill`() = runTest {
        val manager = manager()
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", document("stable", "original")))
        val replacement = document("stable", "replacement")

        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkillFileBytesAtomically("stable", mapOf("SKILL.md" to replacement.toByteArray())))

        assertEquals(replacement, skillsRoot().resolve("stable/SKILL.md").readText())
    }

    @Test
    fun `binary support files are published byte identical`() = runTest {
        val manager = manager()
        val binary = byteArrayOf(0x00, 0xFF.toByte(), 0xC3.toByte(), 0x28)

        assertEquals(
            SkillFileSaveResult.SUCCESS,
            manager.saveSkillFileBytesAtomically(
                "binary-skill",
                mapOf(
                    "SKILL.md" to document("binary-skill", "binary support").toByteArray(),
                    "assets/blob.bin" to binary,
                ),
            ),
        )

        assertArrayEquals(binary, skillsRoot().resolve("binary-skill/assets/blob.bin").readBytes())
    }

    @Test
    fun `editing the skill document preserves support files`() = runTest {
        val manager = manager()
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", document("stable", "original")))
        assertEquals(
            SkillFileSaveResult.SUCCESS,
            manager.saveSkillFile("stable", "references/notes.md", "keep me"),
        )

        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", document("stable", "updated")))

        assertEquals(
            SkillContentReadResult.Success("keep me"),
            manager.readSkillContent("stable", "references/notes.md"),
        )
    }

    @Test
    fun `support file deletion is staged and skill document deletion is rejected`() = runTest {
        val manager = manager()
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", document("stable", "original")))
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkillFile("stable", "notes.md", "body"))

        assertEquals(
            SkillFileDeleteResult.PROTECTED_SKILL_FILE,
            manager.deleteSkillFile("stable", "SKILL.md"),
        )
        assertEquals(
            SkillFileDeleteResult.SUCCESS,
            manager.deleteSkillFile("stable", "notes.md"),
        )

        assertTrue(skillsRoot().resolve("stable/SKILL.md").isFile)
        assertFalse(skillsRoot().resolve("stable/notes.md").exists())
        assertTrue(skillsRoot().listFiles().orEmpty().none { it.name.contains(".staging.") })
    }

    @Test
    fun `orphan backup is recovered before the skill is listed`() = runTest {
        val manager = manager()
        val original = document("stable", "original")
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", original))
        val target = skillsRoot().resolve("stable")
        val backup = skillsRoot().resolve(".${"stable".hashCode().toUInt().toString(16)}.backup.0.tmp")
        assertTrue(target.renameTo(backup))

        assertEquals(listOf("stable"), manager.listSkills().map { it.name })

        assertEquals(original, skillsRoot().resolve("stable/SKILL.md").readText())
        assertFalse(backup.exists())
    }

    @Test
    fun `locked Settings rejection restores the published skill`() = runTest {
        val settingsStore = mockk<SettingsStore>()
        coEvery { settingsStore.updateLocal(any()) } throws SettingsLockedException("records/assistants", "Managed")
        val manager = manager(settingsStore)
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("stable", document("stable", "original")))

        val error = runCatching { manager.deleteSkill("stable") }.exceptionOrNull()

        assertTrue(error is SettingsLockedException)
        assertTrue(skillsRoot().resolve("stable/SKILL.md").isFile)
        assertTrue(skillsRoot().listFiles().orEmpty().none { ".backup." in it.name })
    }

    @Test
    fun `orphan bundle backup restores the complete previous root`() = runTest {
        val manager = manager()
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("first", document("first", "original")))
        val root = skillsRoot()
        val backup = temporaryFolder.root.resolve(".${FileFolders.SKILLS}.bundle.backup.0.tmp")
        assertTrue(root.renameTo(backup))

        assertEquals(listOf("first"), manager.listSkills().map { it.name })

        assertTrue(root.resolve("first/SKILL.md").isFile)
        assertFalse(backup.exists())
    }

    @Test
    fun `invalid bundle member preserves every previously published skill`() = runTest {
        val manager = manager()
        val first = document("first", "old first")
        val second = document("second", "old second")
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("first", first))
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("second", second))

        val result = manager.importSkillBundleAtomically(
            listOf(
                SkillImportBundleEntry("first", mapOf("SKILL.md" to document("first", "new first").toByteArray())),
                SkillImportBundleEntry("second", mapOf("SKILL.md" to document("wrong", "invalid").toByteArray())),
            ),
        )

        assertEquals(SkillBundleImportResult.INVALID_BUNDLE, result)
        assertEquals(first, skillsRoot().resolve("first/SKILL.md").readText())
        assertEquals(second, skillsRoot().resolve("second/SKILL.md").readText())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { ".bundle.staging." in it.name })
    }

    @Test
    fun `valid bundle replaces all members through one root publication`() = runTest {
        val manager = manager()
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("first", document("first", "old first")))
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("second", document("second", "old second")))

        val result = manager.importSkillBundleAtomically(
            listOf(
                SkillImportBundleEntry("first", mapOf("SKILL.md" to document("first", "new first").toByteArray())),
                SkillImportBundleEntry(
                    "second",
                    mapOf(
                        "SKILL.md" to document("second", "new second").toByteArray(),
                        "references/info.md" to "support".toByteArray(),
                    ),
                ),
            ),
        )

        assertEquals(SkillBundleImportResult.SUCCESS, result)
        assertEquals(document("first", "new first"), skillsRoot().resolve("first/SKILL.md").readText())
        assertEquals("support", skillsRoot().resolve("second/references/info.md").readText())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { ".bundle." in it.name })
    }

    @Test
    fun `oversized and invalid UTF-8 files fail before text parsing`() = runTest {
        val manager = manager()
        val skillDir = skillsRoot().resolve("bounded").apply { mkdirs() }
        skillDir.resolve("SKILL.md").writeBytes(ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() })

        assertTrue(manager.listSkills().isEmpty())

        skillDir.resolve("SKILL.md").writeText(document("bounded", "valid"))
        skillDir.resolve("large.txt").writeBytes(ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        assertEquals(
            SkillContentReadResult.ResourceLimit,
            manager.readSkillContent("bounded", "large.txt"),
        )

        skillDir.resolve("binary.dat").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals(
            SkillContentReadResult.InvalidEncoding,
            manager.readSkillContent("bounded", "binary.dat"),
        )
    }

    @Test
    fun `invalid directory query retains parser diagnosis and legacy names remain valid`() = runTest {
        val manager = manager()
        val root = skillsRoot()
        root.resolve("empty").mkdir()
        root.resolve("broken").apply { mkdir() }.resolve("SKILL.md").writeText("---\nname: [\n---\n")
        root.resolve("legacy-folder").apply { mkdir() }.resolve("SKILL.md").writeText(document("logical-name", "ok"))
        val invalid = manager.listInvalidSkillDirectories()
        assertEquals(listOf("broken", "empty"), invalid.map { it.directoryName })
        assertTrue(invalid.first().diagnostic.message!!.contains("skill_invalid_frontmatter"))
        assertNotNull(invalid.first().diagnostic.cause)
        assertTrue(invalid.last().diagnostic.message!!.contains("skill_document_missing"))
        assertEquals(listOf("logical-name"), manager.listSkills().map { it.name })
    }

    @Test
    fun `invalid cleanup preserves valid and other invalid trees without altering settings`() = runTest {
        val settings = mockk<SettingsStore>(relaxed = true)
        val manager = manager(settings)
        val root = skillsRoot()
        root.resolve("empty").mkdir()
        root.resolve("other-invalid").apply { mkdir() }.resolve("support").writeText("keep broken contents")
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("valid", document("valid", "keep")))
        val target = manager.listInvalidSkillDirectories().first { it.directoryName == "empty" }
        assertEquals(InvalidSkillDeleteResult.SUCCESS, manager.deleteInvalidSkillDirectory(target))
        assertFalse(root.resolve("empty").exists())
        assertEquals("keep broken contents", root.resolve("other-invalid/support").readText())
        assertEquals(document("valid", "keep"), root.resolve("valid/SKILL.md").readText())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { ".bundle." in it.name })
        io.mockk.coVerify(exactly = 0) { settings.updateLocal(any()) }
    }

    @Test
    fun `repair or replacement invalidates an old cleanup confirmation`() = runTest {
        val manager = manager()
        val root = skillsRoot()
        val targetDir = root.resolve("repair").apply { mkdir() }
        val target = manager.listInvalidSkillDirectories().single()
        assertEquals(SkillFileSaveResult.SUCCESS, manager.saveSkill("repair", document("repair", "fixed")))
        assertEquals(InvalidSkillDeleteResult.CHANGED, manager.deleteInvalidSkillDirectory(target))
        assertTrue(targetDir.resolve("SKILL.md").isFile)

        val invalid = root.resolve("replace").apply { mkdir() }
        val previous = manager.listInvalidSkillDirectories().single()
        assertTrue(invalid.renameTo(temporaryFolder.root.resolve("original-invalid")))
        invalid.mkdir()
        assertEquals(InvalidSkillDeleteResult.CHANGED, manager.deleteInvalidSkillDirectory(previous))
        assertTrue(invalid.isDirectory)
    }

    @Test
    fun `cleanup rejects foreign owner and escaped path without modifying files`() = runTest {
        val manager = manager()
        skillsRoot().resolve("empty").mkdir()
        val target = manager.listInvalidSkillDirectories().single()
        val foreignError = runCatching { manager().deleteInvalidSkillDirectory(target) }.exceptionOrNull()
        assertTrue(foreignError is IllegalArgumentException)
        val escaped = InvalidSkillDirectory("../outside", target.diagnostic, target.owner, target.identity)
        assertEquals(InvalidSkillDeleteResult.CHANGED, manager.deleteInvalidSkillDirectory(escaped))
        assertTrue(skillsRoot().resolve("empty").isDirectory)
    }

    @Test
    fun `cancelled cleanup while waiting leaves original tree and creates no staging`() = runTest {
        val manager = manager()
        skillsRoot().resolve("empty").mkdir()
        val target = manager.listInvalidSkillDirectories().single()
        BackupSnapshotBarrier.withLock {
            val task = launch(kotlinx.coroutines.Dispatchers.IO) {
                manager.deleteInvalidSkillDirectory(target)
            }
            task.cancel()
            task.join()
        }
        assertTrue(skillsRoot().resolve("empty").isDirectory)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { ".bundle." in it.name })
    }

    @Test
    fun `cleanup publication failure preserves directory and original diagnostic for retry`() = runTest {
        val manager = manager()
        val directory = skillsRoot().resolve("empty").apply { mkdir() }
        val target = manager.listInvalidSkillDirectories().single()
        val collision = temporaryFolder.root.resolve(".${FileFolders.SKILLS}.bundle.backup.0.tmp")
        collision.writeText("unrelated occupied path")
        val error = runCatching { manager.deleteInvalidSkillDirectory(target) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("Unable to stage Skill backup"))
        assertTrue(directory.isDirectory)
        assertEquals("unrelated occupied path", collision.readText())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { ".bundle.staging." in it.name })
        assertTrue(collision.delete())
        assertEquals(InvalidSkillDeleteResult.SUCCESS, manager.deleteInvalidSkillDirectory(target))
    }

    @Test
    fun `interrupted invalid cleanup restores original root through existing bundle recovery`() = runTest {
        val manager = manager()
        val root = skillsRoot()
        root.resolve("empty").mkdir()
        root.resolve("broken").apply { mkdir() }.resolve("support").writeText("keep")
        val backup = temporaryFolder.root.resolve(".${FileFolders.SKILLS}.bundle.backup.0.tmp")
        assertTrue(root.renameTo(backup))
        temporaryFolder.root.resolve(".${FileFolders.SKILLS}.bundle.staging.0.tmp").mkdir()
        assertEquals(listOf("broken", "empty"), manager.listInvalidSkillDirectories().map { it.directoryName })
        assertEquals("keep", root.resolve("broken/support").readText())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { ".bundle." in it.name })
    }

    private fun manager(settingsStore: SettingsStore = mockk(relaxed = true)): SkillManager {
        val context = mockk<Context>()
        every { context.filesDir } returns temporaryFolder.root
        return SkillManager(context, settingsStore)
    }

    private fun skillsRoot(): File = temporaryFolder.root.resolve(FileFolders.SKILLS).apply { mkdirs() }

    private fun document(name: String, description: String) =
        "---\nname: $name\ndescription: $description\n---\nbody"
}
