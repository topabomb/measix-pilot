package net.weero.measix.pilot.ui.pages.imggen

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingData
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.imggen.ImageGenerationCoordinator
import net.weero.measix.pilot.data.imggen.ImageGenerationModelDescriptor
import net.weero.measix.pilot.data.imggen.ImageGenerationOutcome
import net.weero.measix.pilot.data.imggen.ImageGenerationRequest
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.FileManagementQueryService
import net.weero.measix.pilot.service.ModelCatalogReadState
import net.weero.measix.pilot.service.ModelCatalogUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImgGenVMTest {
    @Test
    fun `gallery export forwards the page context instead of the application`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val selected = RealmSelection(RealmAccess.Personal, 3)
            val fixture = galleryFixture(owner, MutableStateFlow(selected))
            runCurrent()
            val pageContext = mockk<android.content.Context>()
            val exporter = mockk<net.weero.measix.pilot.service.MediaExportService>()
            val keys = listOf(net.weero.measix.pilot.service.ManagedFileKey.Generated(1, selected))
            coEvery { fixture.commands.saveGeneratedBatch(keys, pageContext, exporter, any()) } returns emptyList()
            fixture.vm.saveGallerySelection(selected, setOf(1), pageContext, exporter) {}
            io.mockk.coVerify(exactly = 1) { fixture.commands.saveGeneratedBatch(keys, pageContext, exporter, any()) }
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test
    fun `gallery batch binds selected IDs to the original selection and publishes each receipt`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val selected = RealmSelection(RealmAccess.Personal, 3)
            val fixture = galleryFixture(owner, MutableStateFlow(selected))
            runCurrent()
            val keys = listOf(1, 4).map { net.weero.measix.pilot.service.ManagedFileKey.Generated(it, selected) }
            val results = keys.map { net.weero.measix.pilot.service.GeneratedImageBatchItemResult.Deleted(it) }
            coEvery { fixture.commands.deleteGeneratedBatch(keys, any()) } coAnswers {
                val callback = secondArg<(net.weero.measix.pilot.service.GeneratedImageBatchItemResult) -> Unit>()
                results.forEach(callback)
                results
            }
            val received = mutableListOf<net.weero.measix.pilot.service.GeneratedImageBatchItemResult>()
            fixture.vm.deleteGallerySelection(selected, linkedSetOf(1, 4), received::add)
            assertEquals(results, received)
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test
    fun `gallery late receipts and reconciliation cannot update another selected space`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val original = RealmSelection(RealmAccess.Personal, 3)
            val selection = MutableStateFlow(original)
            val fixture = galleryFixture(owner, selection)
            runCurrent()
            val released = CompletableDeferred<Unit>()
            coEvery { fixture.commands.deleteGeneratedBatch(any(), any()) } coAnswers {
                val callback = secondArg<(net.weero.measix.pilot.service.GeneratedImageBatchItemResult) -> Unit>()
                released.await()
                val result = net.weero.measix.pilot.service.GeneratedImageBatchItemResult.Deleted(
                    net.weero.measix.pilot.service.ManagedFileKey.Generated(1, original))
                callback(result)
                listOf(result)
            }
            coEvery { fixture.queries.existingGeneratedIds(original, setOf(1)) } coAnswers {
                released.await()
                setOf(1)
            }
            var receipts = 0
            val batch = async { fixture.vm.deleteGallerySelection(original, setOf(1)) { receipts++ } }
            val reconciliation = async { runCatching { fixture.vm.reconcileGallerySelection(original, setOf(1)) } }
            runCurrent()
            selection.value = original.copy(revision = 4)
            runCurrent()
            released.complete(Unit)
            batch.await()
            assertEquals(0, receipts)
            assertEquals("gallery_selection_changed", reconciliation.await().exceptionOrNull()?.message)
            assertEquals("gallery_selection_changed", runCatching {
                fixture.vm.deleteGallerySelection(original, setOf(1)) {}
            }.exceptionOrNull()?.message)
            io.mockk.coVerify(exactly = 1) { fixture.commands.deleteGeneratedBatch(any(), any()) }
        } finally { owner.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    private data class GalleryFixture(
        val vm: ImgGenVM,
        val commands: net.weero.measix.pilot.service.FileManagementApplicationService,
        val queries: FileManagementQueryService,
    )

    private fun galleryFixture(owner: ViewModelStore, selection: MutableStateFlow<RealmSelection>): GalleryFixture {
        val settings = mockk<SettingsStore>()
        every { settings.userSettings } returns MutableStateFlow(Settings())
        val configuration = mockk<ConfigurationQueryService>()
        every { configuration.observeModelCatalog() } returns flowOf(ModelCatalogReadState.Loading)
        val queries = mockk<FileManagementQueryService>()
        every { queries.observeSelection() } returns selection
        every { queries.observeGeneratedPaging() } returns flowOf(PagingData.empty())
        val commands = mockk<net.weero.measix.pilot.service.FileManagementApplicationService>()
        val vm = ImgGenVM(ApplicationProvider.getApplicationContext<Application>(), settings, mockk(), queries, commands,
            configuration, mockk())
        owner.put("gallery", vm)
        return GalleryFixture(vm, commands, queries)
    }

    @Test
    fun `picker target is consumed once and only actual request inputs wait for completion`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        val root = kotlin.io.path.createTempDirectory("reference-owner").toFile()
        try {
            val settings = mockk<SettingsStore>()
            every { settings.userSettings } returns MutableStateFlow(Settings())
            val selected = RealmSelection(RealmAccess.Personal, 0)
            val model = Model(modelId = "image", type = me.rerere.ai.provider.ModelType.IMAGE)
            val catalog = net.weero.measix.pilot.service.userDefinitionModelCatalog(listOf(ProviderSetting.OpenAI(models = listOf(model)))).copy(
                selection = selected, roleSelections = mapOf(net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.IMAGE_MODEL to
                    net.weero.measix.pilot.data.configuration.ConfigurationSelection(model.id, null)))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeModelCatalog() } returns flowOf(ModelCatalogReadState.Available(catalog))
            coEvery { configuration.requireSelection(selected) } returns Unit
            val files = mockk<FileManagementQueryService>()
            every { files.observeSelection() } returns MutableStateFlow(selected)
            every { files.observeGeneratedPaging() } returns flowOf(PagingData.empty())
            val commands = mockk<net.weero.measix.pilot.service.FileManagementApplicationService>()
            var imports = 0
            coEvery { commands.importImageReference(any(), any(), any(), any(), any()) } coAnswers {
                val file = java.io.File(root, "${imports++}.png").apply { writeText("temporary input") }
                net.weero.measix.pilot.service.TemporaryImage(file, selected,
                    net.weero.measix.pilot.service.ImageSource(file.path, net.weero.measix.pilot.service.ImageOrigin.UPLOAD,
                        verifyAccess = {}, readPayload = { file.readBytes() }))
            }
            val started = CompletableDeferred<Unit>()
            val coordinator = mockk<ImageGenerationCoordinator>()
            coEvery { coordinator.enqueue(any()) } coAnswers {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
            }
            val vm = ImgGenVM(ApplicationProvider.getApplicationContext<Application>(), settings, coordinator, files, commands, configuration, mockk())
            owner.put("image", vm)
            runCurrent()
            org.junit.Assert.assertTrue(vm.beginReferenceImport())
            assertFalse(vm.beginReferenceImport())
            val target = requireNotNull(vm.takeReferenceImport())
            org.junit.Assert.assertNull(vm.takeReferenceImport())
            val uris = List(16) { android.net.Uri.parse("content://picked/$it") }
            assertEquals(0, vm.importReferenceImages(target, uris))
            assertEquals(1, vm.importReferenceImages(target, uris.take(1)))
            assertEquals(16, imports)
            runCurrent()
            val captured = vm.referenceImages.value
            vm.updatePrompt("use captured references")
            vm.editImage()
            runCurrent()
            started.await()
            vm.clearReferenceImages()
            org.junit.Assert.assertTrue(captured.all { it.file.exists() })
            org.junit.Assert.assertTrue(vm.beginReferenceImport())
            val fresh = requireNotNull(vm.takeReferenceImport())
            assertEquals(0, vm.importReferenceImages(fresh, uris.take(1)))
            runCurrent()
            val unused = vm.referenceImages.value.single()
            vm.removeReferenceImage(unused)
            assertFalse(unused.file.exists())
            org.junit.Assert.assertTrue(captured.all { it.file.exists() })
            org.junit.Assert.assertTrue(vm.beginReferenceImport())
            vm.startNewSession()
            val revoked = requireNotNull(vm.takeReferenceImport())
            assertFalse(revoked.owner.isActive)
            org.junit.Assert.assertTrue(runCatching { vm.importReferenceImages(revoked, uris) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            release.complete(Unit)
            runCurrent()
            org.junit.Assert.assertTrue(captured.none { it.file.exists() })
        } finally {
            release.complete(Unit)
            owner.clear()
            runCurrent()
            root.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `rapid replacement cannot bypass a cancelled predecessor that still owns cleanup`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val owner = ViewModelStore()
        try {
            val settings = mockk<SettingsStore>()
            every { settings.userSettings } returns MutableStateFlow(Settings())
            val selected = RealmSelection(RealmAccess.Personal, 0)
            val model = Model(modelId = "image", type = me.rerere.ai.provider.ModelType.IMAGE)
            val provider = ProviderSetting.OpenAI(models = listOf(model))
            val catalog = net.weero.measix.pilot.service.userDefinitionModelCatalog(listOf(provider)).copy(
                selection = selected,
                roleSelections = mapOf(net.weero.measix.pilot.data.configuration.ResourceSelectionSlot.IMAGE_MODEL to
                    net.weero.measix.pilot.data.configuration.ConfigurationSelection(model.id, null)),
            )
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeModelCatalog() } returns flowOf(
                ModelCatalogReadState.Available(catalog),
            )
            coEvery { configuration.requireSelection(selected) } returns Unit
            val files = mockk<FileManagementQueryService>()
            every { files.observeSelection() } returns MutableStateFlow(selected)
            every { files.observeGeneratedPaging() } returns flowOf(PagingData.empty())
            val coordinator = mockk<ImageGenerationCoordinator>()
            val started = mutableListOf<String>()
            coEvery { coordinator.enqueue(any()) } coAnswers {
                val request = firstArg<ImageGenerationRequest>()
                started += request.prompt
                if (request.prompt == "A") {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                    }
                } else ImageGenerationOutcome.Success(emptyList())
            }
            val vm = ImgGenVM(ApplicationProvider.getApplicationContext<Application>(), settings,
                coordinator, files, mockk(), configuration, mockk())
            owner.put("image", vm)
            runCurrent()
            vm.updatePrompt("A")
            vm.generateImage()
            runCurrent()
            assertEquals(listOf("A"), started)
            vm.updatePrompt("B")
            vm.generateImage()
            // C replaces B before B's coroutine body starts. Both must retain A's cleanup barrier.
            vm.updatePrompt("C")
            vm.generateImage()
            runCurrent()
            assertEquals(listOf("A"), started)
            cleaning.await()
            vm.updatePrompt("D")
            vm.generateImage()
            runCurrent()
            assertEquals(listOf("A"), started)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("A", "D"), started)
            assertFalse(vm.isGenerating.value)
        } finally {
            release.complete(Unit)
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
