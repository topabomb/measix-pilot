package me.rerere.tts.controller

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import me.rerere.tts.model.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsControllerSchedulingTest {
    private class Fixture(val scope: TestScope) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val audio = mockk<AudioPlayer>(relaxed = true)
        val requested = mutableListOf<String>()
        val responses = mutableMapOf<String, CompletableDeferred<TTSResponse>>()
        val played = mutableListOf<String>()
        val canceled = mutableListOf<String>()
        val playbackEnds = mutableMapOf<String, CompletableDeferred<Unit>>()
        var admission = CompletableDeferred(Unit)
        val controller: TtsController
        init {
            Dispatchers.setMain(dispatcher)
            mockkStatic(android.util.Log::class)
            every { android.util.Log.e(any(), any(), any()) } returns 0
            every { audio.playbackState } returns MutableStateFlow(PlaybackState())
            coEvery { audio.play(any()) } coAnswers {
                val text = firstArg<TTSResponse>().metadata.getValue("text")
                played += text
                playbackEnds.getOrPut(text) { CompletableDeferred() }.await()
            }
            controller = TtsController(audio, { it.toString() }, dispatcher)
            controller.setSession(TtsPlaybackSession(
                synthesize = { chunk ->
                    requested += chunk.text
                    try { responses.getOrPut(chunk.text) { CompletableDeferred() }.await() }
                    finally { canceled += chunk.text }
                },
                admitPlayback = { start -> admission.await(); start() },
            ))
        }
        fun complete(text: String) {
            responses.getValue(text).complete(TTSResponse(byteArrayOf(1), AudioFormat.PCM, metadata = mapOf("text" to text)))
        }
        fun append(text: String) = controller.speak(text, false, queueSessionId = "turn")
        suspend fun close() {
            val cleanup = controller.dispose()
            scope.runCurrent()
            cleanup.awaitClosed()
            Dispatchers.resetMain()
            unmockkStatic(android.util.Log::class)
        }
    }

    @Test fun `current synthesis and admission precede exactly one prefetch despite appends`() = runTest {
        val f = Fixture(this)
        try {
            f.admission = CompletableDeferred()
            f.append("第一段。\n\n第二段。\n\n第三段。")
            runCurrent()
            f.append("第四段。")
            runCurrent()
            assertEquals(listOf("第一段。"), f.requested)
            f.complete("第一段。")
            runCurrent()
            assertTrue(f.played.isEmpty())
            assertEquals(1, f.requested.size)
            f.admission.complete(Unit)
            runCurrent()
            assertEquals(listOf("第一段。"), f.played)
            assertEquals(listOf("第一段。", "第二段。"), f.requested)
            f.append("第五段。")
            runCurrent()
            assertEquals(2, f.requested.size)
        } finally { f.close() }
    }

    @Test fun `pause during synthesis blocks playback and prefetch until resumed`() = runTest {
        val f = Fixture(this)
        try {
            f.append("第一段。\n\n第二段。")
            runCurrent()
            f.controller.pause()
            f.complete("第一段。")
            runCurrent()
            assertTrue(f.played.isEmpty())
            assertEquals(listOf("第一段。"), f.requested)
            f.controller.resume()
            runCurrent()
            assertEquals(listOf("第一段。"), f.played)
            assertEquals(listOf("第一段。", "第二段。"), f.requested)
        } finally { f.close() }
    }

    @Test fun `paused late append waits for resume and stop closes pending prefetch`() = runTest {
        val f = Fixture(this)
        try {
            f.append("第一段。")
            runCurrent()
            f.complete("第一段。")
            runCurrent()
            f.controller.pause()
            f.append("第二段。")
            runCurrent()
            assertEquals(listOf("第一段。"), f.requested)
            f.controller.resume()
            runCurrent()
            assertEquals(listOf("第一段。", "第二段。"), f.requested)
            val cleanup = f.controller.stop()
            runCurrent()
            cleanup.awaitClosed()
            assertTrue("第二段。" in f.canceled)
            assertEquals(PlaybackStatus.Idle, f.controller.playbackState.value.status)
        } finally { f.close() }
    }

    @Test fun `replacement cancels pending synthesis and old worker cannot publish over new queue`() = runTest {
        val f = Fixture(this)
        try {
            f.append("旧第一段。\n\n旧第二段。")
            runCurrent()
            val cleanup = f.controller.speak("新第一句。新第二句。", queueSessionId = "new")
            runCurrent()
            cleanup!!.awaitClosed()
            assertTrue("旧第一段。" in f.canceled)
            assertEquals(listOf("旧第一段。", "新第一句。"), f.requested)
            assertEquals(PlaybackStatus.Buffering, f.controller.playbackState.value.status)
            f.complete("新第一句。")
            runCurrent()
            assertEquals(listOf("新第一句。"), f.played)
            assertEquals(listOf("旧第一段。", "新第一句。", "新第二句。"), f.requested)
        } finally { f.close() }
    }

    @Test fun `pause while admission suspends cannot start playback`() = runTest {
        val f = Fixture(this)
        try {
            f.admission = CompletableDeferred()
            f.append("第一段。\n\n第二段。")
            runCurrent()
            f.complete("第一段。")
            runCurrent()
            f.controller.pause()
            f.admission.complete(Unit)
            runCurrent()
            assertTrue(f.played.isEmpty())
            assertEquals(1, f.requested.size)
            f.controller.resume()
            runCurrent()
            assertEquals(listOf("第一段。"), f.played)
            assertEquals(2, f.requested.size)
        } finally { f.close() }
    }

    @Test fun `prefetched segment is reused and window advances only after playback admission`() = runTest {
        val f = Fixture(this)
        try {
            f.append("第一段。\n\n第二段。\n\n第三段。")
            runCurrent()
            f.complete("第一段。")
            runCurrent()
            f.complete("第二段。")
            runCurrent()
            assertEquals(listOf("第一段。", "第二段。"), f.requested)
            f.playbackEnds.getValue("第一段。").complete(Unit)
            advanceTimeBy(120)
            runCurrent()
            assertEquals(listOf("第一段。", "第二段。"), f.played)
            assertEquals(listOf("第一段。", "第二段。", "第三段。"), f.requested)
        } finally { f.close() }
    }

    @Test fun `failed synthesis advances without retry or speculative requests`() = runTest {
        val f = Fixture(this)
        try {
            f.append("失败段。\n\n第二段。\n\n第三段。")
            runCurrent()
            f.responses.getValue("失败段。").completeExceptionally(java.io.IOException("upstream failed"))
            runCurrent()
            assertEquals(listOf("失败段。", "第二段。"), f.requested)
            assertTrue(f.played.isEmpty())
            assertTrue(f.controller.error.value!!.contains("upstream failed"))
            f.complete("第二段。")
            runCurrent()
            assertEquals(listOf("第二段。"), f.played)
            assertEquals(listOf("失败段。", "第二段。", "第三段。"), f.requested)
        } finally { f.close() }
    }

    @Test fun `active append stays grouped but append after natural end gets a short opening`() = runTest {
        val f = Fixture(this)
        try {
            f.append("第一段。")
            runCurrent()
            f.complete("第一段。")
            runCurrent()
            val appended = "追加第一句。追加第二句。"
            f.append(appended)
            runCurrent()
            assertEquals(listOf("第一段。", appended), f.requested)
            f.complete(appended)
            f.playbackEnds.getValue("第一段。").complete(Unit)
            advanceTimeBy(120)
            runCurrent()
            f.playbackEnds.getValue(appended).complete(Unit)
            runCurrent()
            assertEquals(PlaybackStatus.Ended, f.controller.playbackState.value.status)
            f.append("迟到第一句。迟到第二句。")
            runCurrent()
            assertEquals("迟到第一句。", f.requested.last())
            assertFalse("迟到第二句。" in f.requested)
        } finally { f.close() }
    }
}
