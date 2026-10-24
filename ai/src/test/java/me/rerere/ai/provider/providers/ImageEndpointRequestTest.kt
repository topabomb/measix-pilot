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

    @Test fun `new image model parameters are validated after overrides before any request`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = client(requests)
        try {
            val provider = OpenAIProvider(client)
            val setting = ProviderSetting.OpenAI(apiKey = "fixture")
            for (model in listOf("gpt-image-2.5-sunburst", "gpt-image-2.5-flare-2026-09-08")) {
                for (custom in listOf(
                    listOf(CustomBody("quality", JsonPrimitive("hd"))),
                    listOf(CustomBody("background", JsonPrimitive("transparent")), CustomBody("output_format", JsonPrimitive("jpeg"))),
                    listOf(CustomBody("output_compression", JsonPrimitive(50))),
                    listOf(CustomBody("response_format", JsonPrimitive("url"))),
                )) {
                    try {
                        provider.generateImage(setting, ImageGenerationParams(Model(modelId = model), "draw", customBody = custom)).toList()
                        fail("invalid generation accepted")
                    } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("openai_image_parameter_invalid")) }
                    try {
                        provider.editImage(setting, ImageEditParams(Model(modelId = model), "edit", listOf("missing.png"), customBody = custom)).toList()
                        fail("invalid edit accepted")
                    } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("openai_image_parameter_invalid")) }
                }
            }
            for (routed in listOf(false, true)) {
                for (size in listOf("1025x1024", "4096x1024", "0x1024", "99999999999999x1024")) {
                    val params = ImageGenerationParams(Model(modelId = "gpt-image-2.5-flare"), "draw", size = size)
                    try {
                        if (routed) me.rerere.ai.provider.images.ManagedImageGenerationProvider(client).generate(
                            me.rerere.ai.provider.images.ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS,
                            params, emptyList(), RequestCredentials.Routed("https://relay.test/images", "fixture"),
                        ).toList() else provider.generateImage(setting, params).toList()
                        fail("invalid size accepted")
                    } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("field=size")) }
                }
            }
            assertTrue(requests.isEmpty())
            for (quality in listOf("auto", "low", "medium", "high", "xhigh", "max")) {
                provider.generateImage(setting, ImageGenerationParams(Model(modelId = "gpt-image-2.5-sunburst"), "draw", size = "1536x864",
                    customBody = listOf(CustomBody("quality", JsonPrimitive(quality)), CustomBody("background", JsonPrimitive("transparent")),
                        CustomBody("output_format", JsonPrimitive("webp")), CustomBody("output_compression", JsonPrimitive(90))))).toList()
                assertEquals(quality, requests.last().bodyJson()["quality"]!!.jsonPrimitive.content)
                assertEquals("1536x864", requests.last().bodyJson()["size"]!!.jsonPrimitive.content)
            }
            provider.generateImage(setting, ImageGenerationParams(Model(modelId = "unknown-image-model"), "draw", size = "1025x1",
                customBody = listOf(CustomBody("quality", JsonPrimitive("custom"))))).toList()
            assertEquals(7, requests.size)
            val source = temporary.newFile("policy.png").apply { writeBytes(byteArrayOf(1)) }
            provider.editImage(setting, ImageEditParams(Model(modelId = "gpt-image-2.5-flare"), "edit", listOf(source.path),
                customBody = listOf(CustomBody("quality", JsonPrimitive("hd")), CustomBody("quality", JsonPrimitive("max")),
                    CustomBody("background", JsonPrimitive("transparent"))))).toList()
            val multipart = requests.last().body as MultipartBody
            val qualities = multipart.parts.filter { it.headers?.get("Content-Disposition")?.contains("name=\"quality\"") == true }
            assertEquals(1, qualities.size)
            assertEquals("max", Buffer().also { qualities.single().body.writeTo(it) }.readUtf8())
        } finally { client.closeFixture() }
    }

    @Test fun `empty successful image response fails on both user and routed provider paths`() = runBlocking {
        for (routed in listOf(false, true)) {
            val requests = mutableListOf<Request>()
            val client = client(requests, response = """{"data":[]}""")
            try {
                val params = ImageGenerationParams(Model(modelId = "gpt-image-2.5-sunburst"), "draw")
                try {
                    if (routed) me.rerere.ai.provider.images.ManagedImageGenerationProvider(client).generate(
                        me.rerere.ai.provider.images.ImageGenerationClientProtocol.OPENAI_IMAGES_GENERATIONS, params,
                        emptyList(), RequestCredentials.Routed("https://relay.test/images", "fixture"),
                    ).toList()
                    else OpenAIProvider(client).generateImage(ProviderSetting.OpenAI(apiKey = "fixture"), params).toList()
                    fail("empty image response completed successfully")
                } catch (error: HttpException) {
                    assertEquals("image_generation_empty_result", error.errorCode)
                }
                assertEquals(1, requests.size)
            } finally { client.closeFixture() }
        }
    }

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

    private fun client(requests: MutableList<Request>, status: Int = 200, response: String? = null) = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body((response ?: if (status == 200) """{"data":[{"b64_json":"AA==","media_type":"image/webp"}]}"""
                else """{"error":{"code":"rate_limit_exceeded","message":"fixture quota exhausted"}}""").toResponseBody()).build()
    }.build()

    private fun Request.bodyJson() = Json.parseToJsonElement(Buffer().also { body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun OkHttpClient.closeFixture() { dispatcher.executorService.shutdown(); connectionPool.evictAll() }
}
