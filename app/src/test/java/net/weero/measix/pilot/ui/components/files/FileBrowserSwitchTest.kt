package net.weero.measix.pilot.ui.components.files

import android.content.Context
import androidx.lifecycle.ViewModelStore
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.files.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserSwitchTest {
    private class Browser(source: FileBrowserSource) : FileBrowserVM(source, mockk<Context>()) {
        suspend fun switch(change: () -> Unit) = resetTarget(change)
    }

    private fun session() = mockk<FileBrowserSession>(relaxed = true) {
        coEvery { list("") } returns BrowserDirectory("", emptyList(), null, null)
        coEvery { isValid() } returns true
    }

    @Test fun `late invalidation of old server does not revoke or close new server`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val changes = MutableSharedFlow<Unit>()
            val first = session()
            val second = session()
            var target = first
            val source = mockk<FileBrowserSource> {
                every { selection } returns RealmSelection(RealmAccess.Personal, 1)
                every { this@mockk.changes } returns changes
                coEvery { open() } coAnswers { target }
                coEvery { refresh() } returns Unit
            }
            val vm = Browser(source).also { store.put("browser", it) }
            runCurrent()
            assertSame(first, vm.state.value.handle)
            val answer = CompletableDeferred<Boolean>()
            coEvery { first.isValid() } coAnswers { answer.await() }
            changes.emit(Unit)
            runCurrent()
            vm.switch { target = second }
            runCurrent()
            assertSame(second, vm.state.value.handle)
            answer.complete(false)
            runCurrent()
            assertSame(second, vm.state.value.handle)
            assertFalse(vm.state.value.revoked)
            coVerify(exactly = 0) { second.close() }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `temporary authority query failure keeps editor text and later confirmed revocation still closes session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val changes = MutableSharedFlow<Unit>()
            val session = session()
            val source = mockk<FileBrowserSource> {
                every { selection } returns RealmSelection(RealmAccess.Personal, 1)
                every { this@mockk.changes } returns changes
                coEvery { open() } returns session
            }
            val vm = Browser(source).also { store.put("browser", it) }
            runCurrent()
            vm.openPreview(BrowserFile("note.txt", false, 4, null, "\"v1\""))
            val editor = requireNotNull(vm.state.value.editorSession)
            editor.editor.replaceText("Unsaved draft")
            coEvery { session.isValid() } throws java.io.IOException("Temporary authority query failure")
            changes.emit(Unit); runCurrent()
            assertSame(editor, vm.state.value.editorSession)
            assertEquals("Unsaved draft", editor.editor.snapshot())
            assertFalse(vm.state.value.revoked)
            assertTrue(vm.state.value.error!!.contains("Temporary authority query failure"))
            coVerify(exactly = 0) { session.close() }
            coEvery { session.isValid() } returns false
            changes.emit(Unit); runCurrent()
            assertTrue(vm.state.value.revoked)
            coVerify(exactly = 1) { session.close() }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `exit during server switch waits for cleanup and prevents opening new server`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val first = session()
            val source = mockk<FileBrowserSource> {
                every { selection } returns RealmSelection(RealmAccess.Personal, 1)
                every { changes } returns MutableSharedFlow<Unit>()
                coEvery { open() } returns first
            }
            val vm = Browser(source).also { store.put("browser", it) }
            runCurrent()
            val close = CompletableDeferred<Unit>()
            coEvery { first.close() } coAnswers { close.await() }
            var changed = false
            val switching = launch { vm.switch { changed = true } }
            runCurrent()
            val exit = launch { vm.leave() }
            runCurrent()
            assertFalse(exit.isCompleted)
            close.complete(Unit)
            switching.join(); exit.join(); runCurrent()
            assertFalse(changed)
            assertNull(vm.state.value.handle)
            coVerify(exactly = 1) { source.open() }
            coVerify(exactly = 1) { first.close() }
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
