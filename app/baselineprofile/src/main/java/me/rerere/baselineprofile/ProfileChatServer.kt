package me.rerere.baselineprofile

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device loopback only; no production credentials, endpoints or default settings are patched. */
internal class ProfileChatServer(private val responseBody: ((String) -> String)? = null) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicReference<Socket?>()
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closed = false
    val port: Int get() = server.localPort

    init {
        executor.execute {
            try {
                while (!closed) server.accept().use { socket ->
                    active.set(socket)
                    if (closed) return@use
                    socket.soTimeout = 30_000
                    respond(socket)
                    active.compareAndSet(socket, null)
                }
            } catch (error: Exception) {
                if (!closed) failure.set(error)
            }
        }
    }

    fun checkHealthy() { failure.get()?.let { throw AssertionError("Profile model failed", it) } }

    private fun respond(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line(): String = buildString {
            while (true) {
                val byte = input.read()
                check(byte >= 0) { "Unexpected end of HTTP headers" }
                if (byte == 10) break
                if (byte != 13) append(byte.toChar())
                check(length < 8192)
            }
        }
        check(line().startsWith("POST /v1/chat/completions "))
        var size = -1
        var headerCount = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            check(++headerCount < 100)
            if (header.startsWith("Content-Length:", ignoreCase = true)) size = header.substringAfter(':').trim().toInt()
        }
        require(size in 1..1_048_576)
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            check(count > 0) { "Unexpected end of request body" }
            read += count
        }
        val request = JSONObject(bytes.toString(Charsets.UTF_8))
        if (!request.getBoolean("stream")) {
            // Automatic titles use the same configured model through a non-streaming request.
            val reply = JSONObject().put("id", "profile-title").put("object", "chat.completion")
                .put("model", "pilot-profile").put("created", 1)
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", "Profile title"))))
                .put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 3).put("total_tokens", 23))
                .toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(reply)
                flush()
            }
            return
        }
        val messages = request.getJSONArray("messages")
        val lastUser = (0 until messages.length()).map(messages::getJSONObject).last { it.getString("role") == "user" }
        val marker = Regex("PROFILE_[a-f0-9]+").find(lastUser.get("content").toString())?.value
            ?: error("Profile request marker missing")
        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
        fun event(delta: JSONObject, finish: Any = JSONObject.NULL) {
            val chunk = JSONObject().put("id", marker).put("object", "chat.completion.chunk")
                .put("model", "pilot-profile").put("created", 1)
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish)))
            output.write("data: $chunk\n\n")
            output.flush()
        }
        event(JSONObject().put("role", "assistant"))
        event(JSONObject().put("reasoning_content", "Check the table, formula and code before replying."))
        val body = responseBody?.invoke(marker) ?: """
            # Profile reply

            This response exercises **formatted text**, a [local example](https://example.com), and a table.

            | Item | Value |
            | --- | --- |
            | Alpha | 12 |
            | Beta | 34 |

            ${'$'}${'$'}x^2 + y^2 = z^2${'$'}${'$'}

            ```kotlin
            fun total(values: List<Int>) = values.sum()
            ```

            ${List(18) { "Paragraph ${it + 1}: streaming content for a scrollable conversation." }.joinToString("\n\n            ")}

            PROFILE_REPLY_DONE_$marker
        """.trimIndent()
        body.chunked(48).forEach { text ->
            event(JSONObject().put("content", text))
            Thread.sleep(8)
        }
        // Seeing the final text is insufficient: the real Turn must consume this terminal event.
        Thread.sleep(250)
        event(JSONObject(), "stop")
        output.write("data: [DONE]\n\n")
        output.flush()
    }

    override fun close() {
        closed = true
        server.close()
        active.getAndSet(null)?.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Profile model thread did not stop" }
        checkHealthy()
    }
}
