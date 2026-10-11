package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.HttpException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageEndpointRequestTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `OpenRouter generation and reference edits use one endpoint and preserve response MIME`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(requests)
        try {
            val provider = OpenAIProvider(client)
            val setting = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1/", apiKey = "fixture",
                customHeaders = listOf(CustomHeader("X-Shared", "provider"), CustomHeader("X-Provider", "kept")))
            val model = Model(modelId = "organization/team/image-model")
            val generated = provider.generateImage(setting, ImageGenerationParams(model, "draw", size = "2048x1536")).toList()
            val source = temporary.newFile("source.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val edited = provider.editImage(setting, ImageEditParams(model, "edit", listOf(source.path), size = "auto",
                customHeaders = listOf(CustomHeader("x-shared", "request")))).toList()
            assertEquals(2, requests.size)
            requests.forEach { assertEquals("https://openrouter.ai/api/v1/images", it.url.toString()) }
            assertEquals("image/webp", generated.single().mimeType)
            assertEquals("image/webp", edited.single().mimeType)
            val generation = requests[0].bodyJson()
            assertEquals("2048x1536", generation["size"]!!.jsonPrimitive.content)
            assertFalse(generation.containsKey("input_references"))
            val edit = requests[1].bodyJson()
            assertFalse(edit.containsKey("size"))
            val reference = edit["input_references"]!!.jsonArray.single().jsonObject
            assertEquals("image_url", reference["type"]!!.jsonPrimitive.content)
            assertEquals("data:image/webp;base64,AQID", reference["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertEquals(listOf("request"), requests[1].headers.values("X-Shared"))
            assertEquals("kept", requests[1].header("X-Provider"))
        } finally { client.closeFixture() }
    }

    @Test fun `compatible size survives lookalike hosts and grok names while auto is omitted`() = runBlocking {
        for (host in listOf("proxy-x.ai.example", "xxx-max.ai", "openrouter.ai.example", "compatible.test", "api.x.ai")) {
            for (size in listOf("2048x1024", "auto", "AUTO", "")) {
                val requests = mutableListOf<Request>()
                val client = client(requests)
                try {
                    OpenAIProvider(client).generateImage(ProviderSetting.OpenAI(baseUrl = "https://$host/prefix/v1", apiKey = "fixture"),
                        ImageGenerationParams(Model(modelId = "grok-custom"), "draw", size = size)).toList()
                    val request = requests.single()
                    assertEquals("/prefix/v1/images/generations", request.url.encodedPath)
                    assertEquals(size.takeIf { it == "2048x1024" && host != "api.x.ai" }, request.bodyJson()["size"]?.jsonPrimitive?.content)
                } finally { client.closeFixture() }
            }
        }
    }

    @Test fun `OpenRouter failure retains remote diagnostic and sends no fallback edit request`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(requests, status = 429)
        try {
            val source = temporary.newFile("failure.png").apply { writeBytes(byteArrayOf(1)) }
            try {
                OpenAIProvider(client).editImage(ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1", apiKey = "fixture"),
                    ImageEditParams(Model(modelId = "org/model"), "edit", listOf(source.path))).toList()
                fail("HTTP rejection did not propagate")
            } catch (error: HttpException) {
                assertEquals(429, error.statusCode)
                assertTrue(error.message.orEmpty().contains("fixture quota exhausted"))
            }
            assertEquals(1, requests.size)
        } finally { client.closeFixture() }
    }

    private fun client(requests: MutableList<Request>, status: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body((if (status == 200) """{"data":[{"b64_json":"AA==","media_type":"image/webp"}]}"""
                else """{"error":{"code":"rate_limit_exceeded","message":"fixture quota exhausted"}}""").toResponseBody()).build()
    }.build()

    private fun Request.bodyJson() = Json.parseToJsonElement(Buffer().also { body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun OkHttpClient.closeFixture() { dispatcher.executorService.shutdown(); connectionPool.evictAll() }
}
