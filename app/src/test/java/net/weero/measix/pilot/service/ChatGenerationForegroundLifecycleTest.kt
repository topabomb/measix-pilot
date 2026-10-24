package net.weero.measix.pilot.service

import android.app.Application
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid
import net.weero.measix.pilot.AppScope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatGenerationForegroundLifecycleTest {
    private fun active() = mapOf(Uuid.random() to setOf(ConversationActivity.RESPONSE_GENERATION))

    @Test
    fun `timeout releases its start immediately and app command survives service destruction`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val appScope = AppScope(dispatcher)
        val commands = mockk<ConversationApplicationService>()
        coEvery { commands.stopForForegroundTimeout() } returns Unit
        startKoin { modules(module { single { commands }; single { appScope } }) }
        val service = spyk(Robolectric.buildService(ChatGenerationForegroundService::class.java).create().get())
        every { service.stopSelf(any()) } just Runs
        try {
            service.onTimeout(41, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            verify(exactly = 1) { service.stopSelf(41) }
            coVerify(exactly = 0) { commands.stopForForegroundTimeout() }
            service.onDestroy()
            runCurrent()
            coVerify(exactly = 1) { commands.stopForForegroundTimeout() }
        } finally {
            service.onDestroy()
            appScope.cancel()
            runCurrent()
            stopKoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `new start retires queued idle from the previous observer`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val query = mockk<ConversationQueryService>()
        val first = MutableStateFlow(active())
        val second = MutableStateFlow(active())
        every { query.conversationActivities() } returnsMany listOf(first, second)
        startKoin { modules(module { single { query } }) }
        val service = spyk(Robolectric.buildService(ChatGenerationForegroundService::class.java).create().get())
        every { service.getString(any()) } returns "Generation keep-alive"
        every { service.startForeground(any(), any(), any()) } just Runs
        every { service.stopSelfResult(any()) } returns false
        try {
            service.onStartCommand(null, 0, 11)
            runCurrent()
            first.value = emptyMap()
            // The old observer has a scheduled idle emission, but the new start wins Main.
            service.onStartCommand(null, 0, 12)
            runCurrent()
            verify(exactly = 0) { service.stopSelfResult(any()) }
            second.value = emptyMap()
            runCurrent()
            verify(exactly = 1) { service.stopSelfResult(12) }
            verify(exactly = 0) { service.stopSelf() }
        } finally {
            service.onDestroy()
            runCurrent()
            stopKoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `idle request retains its start ID and a later start observes anew`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val query = mockk<ConversationQueryService>()
        val activities = MutableStateFlow<Map<Uuid, Set<ConversationActivity>>>(emptyMap())
        every { query.conversationActivities() } returns activities
        startKoin { modules(module { single { query } }) }
        val service = spyk(Robolectric.buildService(ChatGenerationForegroundService::class.java).create().get())
        every { service.getString(any()) } returns "Generation keep-alive"
        every { service.startForeground(any(), any(), any()) } just Runs
        // A pending system start can reject an older stop; keep observing on the next start.
        every { service.stopSelfResult(any()) } returns false
        try {
            service.onStartCommand(null, 0, 21)
            runCurrent()
            verify(exactly = 1) { service.stopSelfResult(21) }
            activities.value = active()
            service.onStartCommand(null, 0, 22)
            runCurrent()
            verify(exactly = 0) { service.stopSelfResult(22) }
            service.onDestroy()
            activities.value = emptyMap()
            runCurrent()
            verify(exactly = 0) { service.stopSelfResult(22) }
        } finally {
            service.onDestroy()
            runCurrent()
            stopKoin()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `platform rejection stops only its own start without subscribing or cancelling turns`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val query = mockk<ConversationQueryService>()
        val commands = mockk<ConversationApplicationService>()
        startKoin { modules(module { single { query }; single { commands } }) }
        val service = spyk(Robolectric.buildService(ChatGenerationForegroundService::class.java).create().get())
        every { service.getString(any()) } returns "Generation keep-alive"
        val denied = SecurityException("test foreground type rejected")
        every { service.startForeground(any(), any(), any()) } throws denied
        every { service.stopSelfResult(any()) } returns false
        try {
            service.onStartCommand(null, 0, 31)
            runCurrent()
            assertSame(denied, GenerationForegroundDiagnostics.lastRejection)
            verify(exactly = 1) { service.stopSelfResult(31) }
            verify { query wasNot Called; commands wasNot Called }
        } finally {
            service.onDestroy()
            runCurrent()
            stopKoin()
            Dispatchers.resetMain()
        }
    }
}
