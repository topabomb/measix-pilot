package me.rerere.tts.provider.providers

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MiMoTTSProviderTest {
    @Test
    fun decode_audio_data_from_sse_chunk() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val data = """{"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}"""

        val actual = decodeMiMoAudioData(data)

        assertNotNull(actual)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun ignore_sse_chunk_without_audio_data() {
        val data = """{"choices":[{"delta":{"content":"hello"}}]}"""
        assertNull(decodeMiMoAudioData(data))
    }

    @Test
    fun complete_audio_preserves_multiline_sse_framing() {
        val wire = ": comment\r\n\r\ndata: {\r\ndata: \"choices\":[{\"delta\":{\"audio\":{\"data\":\"CQgH\"}}}]}\r\n\r\ndata: [DONE]\r\n\r\n"
        assertArrayEquals(byteArrayOf(9, 8, 7), readMiMoAudio(Buffer().writeUtf8(wire)))
    }

    @Test(expected = IllegalStateException::class)
    fun throws_when_stream_completes_without_audio() {
        readMiMoAudio(Buffer().writeUtf8("data: [DONE]\n\n"))
    }

    @Test
    fun request_body_omits_user_message_when_voice_design_prompt_blank() {
        // 标准模型：voiceDesignPrompt 为空时，messages 只有 assistant 消息，无 user 消息
        val json = buildMiMoRequestBody(
            model = "mimo-v2.5-tts",
            voice = "mimo_default",
            text = "hello",
            voiceDesignPrompt = ""
        ).toString()

        assertFalse("should not contain voice_design_prompt field", json.contains("voice_design_prompt"))
        assertFalse("should not contain user role when prompt blank", json.contains("\"role\":\"user\""))
        assertTrue("text must be in assistant message", json.contains("\"role\":\"assistant\""))
        assertTrue("text content preserved", json.contains("hello"))
        // 标准模型：audio 里包含 voice 字段
        assertTrue("standard model includes audio.voice", json.contains("\"voice\":\"mimo_default\""))
    }

    @Test
    fun request_body_includes_user_message_when_voice_design_prompt_present() {
        // voicedesign 模型：voiceDesignPrompt 非空时，构造 user 消息放描述，assistant 消息仍放待合成文本
        val prompt = "Bright, bouncy, slightly sing-song tone"
        val json = buildMiMoRequestBody(
            model = "mimo-v2.5-tts-voicedesign",
            voice = "mimo_default",
            text = "你好世界",
            voiceDesignPrompt = prompt
        ).toString()

        assertTrue("user message carries the design prompt", json.contains("\"role\":\"user\""))
        assertTrue("design prompt content preserved", json.contains(prompt))
        assertTrue("assistant message still carries text to synthesize", json.contains("\"role\":\"assistant\""))
        assertTrue("synthesis text preserved", json.contains("你好世界"))
        assertFalse("must not put prompt in audio object", json.contains("voice_design_prompt"))
        // voicedesign 模型：音色由 user message 决定，audio 不含 voice（遵循官方示例）
        assertFalse("voicedesign omits audio.voice", json.contains("\"voice\""))
    }

    @Test
    fun voicedesign_model_without_prompt_omits_user_message() {
        // voicedesign 模型但 voiceDesignPrompt 为空：不构造 user 消息，audio 无 voice
        // 实际使用中 UI 会标红阻止，但纯函数层面应正确处理
        val json = buildMiMoRequestBody(
            model = "mimo-v2.5-tts-voicedesign",
            voice = "mimo_default",
            text = "hello",
            voiceDesignPrompt = ""
        ).toString()

        assertFalse("no user message when prompt blank", json.contains("\"role\":\"user\""))
        assertTrue("assistant message present", json.contains("\"role\":\"assistant\""))
        assertFalse("voicedesign omits audio.voice", json.contains("\"voice\""))
    }
}
