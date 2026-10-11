package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MCP OAuth loopback 回调接收器（RFC 8252）。
 *
 * 它只拥有一次授权所需的 loopback socket 与 callback session：
 * - 只绑定 loopback；旧 IP 合同优先 127.0.0.1，新 localhost 合同绑定全部本机解析地址；
 * - 每次授权绑定 OS 分配的 ephemeral port（port 0），redirect path 固定为 [CALLBACK_PATH]；
 * - 只有 exact path、exact state 且恰好携带 code/error 的请求才能完成 session；
 * - 无效或畸形请求只结束当前连接，listener 继续等待合法回调；
 * - request line/header/query 均有硬上限，响应带 `Cache-Control: no-store`。
 *
 * 固定 path 是 client registration identity 的一部分；RFC 8252 只允许 loopback 端口变化，
 * 不允许每次授权随机改变 path。会话防伪由 coordinator 生成的高熵 state 独占。
 */
internal class McpOAuthCallbackServer {

    data class Callback(
        val state: String,
        val code: String?,
        val error: String?,
    )

    private val ownership = Any()
    private val listeners = mutableListOf<ServerSocket>()
    private val clients = mutableSetOf<Socket>()
    private val workers = mutableListOf<Thread>()
    private var started = false
    private var closed = false
    private var redirectUriValue: String? = null
    private var expectedStateValue: String? = null
    private val pending = CompletableFuture<Callback?>()

    val redirectUri: String
        get() = checkNotNull(redirectUriValue) { "callback server not started" }

    /** Every address advertised by localhost must reach the same one-shot session and ephemeral port. */
    fun start(expectedState: String, hostPolicy: McpOAuthCallbackHost = McpOAuthCallbackHost.IP) {
        synchronized(ownership) {
            check(!started && !closed) { "callback server already started or closed" }
            require(expectedState.isNotBlank()) { "OAuth callback state must not be blank" }
            expectedStateValue = expectedState
            try {
                if (hostPolicy == McpOAuthCallbackHost.LOCALHOST) {
                    val addresses = InetAddress.getAllByName("localhost").distinct()
                    check(addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) {
                        "oauth_callback_localhost_not_loopback"
                    }
                    addresses.forEach { address ->
                        listeners += bind(address, listeners.firstOrNull()?.localPort ?: 0)
                    }
                } else {
                    var failure: IOException? = null
                    for (address in listOf("127.0.0.1", "::1")) {
                        try {
                            listeners += bind(InetAddress.getByName(address), 0)
                            break
                        } catch (error: IOException) { failure = error }
                    }
                    if (listeners.isEmpty()) throw IOException("oauth_callback_loopback_bind_failed", failure)
                }
                val socket = listeners.first()
                val address = requireNotNull(socket.inetAddress.hostAddress)
                val host = if (hostPolicy == McpOAuthCallbackHost.LOCALHOST) "localhost"
                    else if (address.contains(':')) "[$address]" else address
                redirectUriValue = "http://$host:${socket.localPort}$CALLBACK_PATH"
                started = true
                listeners.forEach { listener ->
                    workers += Thread({ acceptLoop(listener) }, "mcp-oauth-callback").apply { isDaemon = true }
                }
                workers.forEach(Thread::start)
            } catch (error: Exception) {
                listeners.forEach { socket -> try { socket.close() } catch (cleanup: IOException) { error.addSuppressed(cleanup) } }
                closed = true
                throw error
            }
        }
    }

    suspend fun awaitCallback(timeout: Duration): Callback? {
        if (!synchronized(ownership) { started && !closed }) return null
        return try {
            withTimeoutOrNull(timeout.inWholeMilliseconds) { pending.awaitCancellable() }
        } finally {
            // Socket ownership has been acquired; stop includes accepted reads and actual worker exit.
            close()
        }
    }

    fun close() {
        val ownedWorkers = synchronized(ownership) {
            closed = true
            var failure: IOException? = null
            val sockets: List<java.io.Closeable> = listeners + clients
            sockets.forEach { socket ->
                try { socket.close() } catch (error: IOException) {
                    val previous = failure
                    if (previous == null) failure = error else previous.addSuppressed(error)
                }
            }
            pending.complete(null)
            workers.toList() to failure
        }
        ownedWorkers.first.filter { it !== Thread.currentThread() }.forEach(Thread::join)
        ownedWorkers.second?.let { throw it }
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (!socket.isClosed && !pending.isDone) {
                val client = try { socket.accept() } catch (error: SocketException) {
                    if (socket.isClosed) break else throw error
                }
                val accepted = synchronized(ownership) {
                    if (closed) false else { clients += client; true }
                }
                if (!accepted) { client.close(); break }
                val callback = try {
                    processCallback(client)
                } catch (_: SocketTimeoutException) {
                    null
                } catch (_: RequestLimitExceededException) {
                    writeAndClose(client, 431, "Request Header Fields Too Large")
                    null
                } catch (error: IOException) {
                    if (!synchronized(ownership) { closed }) {
                        android.util.Log.d("McpOAuthCallback", "Callback connection ended", error)
                    }
                    null
                } finally {
                    client.close()
                    synchronized(ownership) { clients -= client }
                }
                if (callback != null) { pending.complete(callback); break }
            }
        } catch (error: Exception) {
            pending.completeExceptionally(error)
        } finally {
            socket.close()
        }
    }

    private fun bind(address: InetAddress, port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(address, port))
            return socket
        } catch (error: Exception) {
            try { socket.close() } catch (cleanup: IOException) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** 返回 null 表示当前连接无效，但不能消费整个授权 session。 */
    private fun processCallback(client: Socket): Callback? {
        if (!client.inetAddress.isLoopbackAddress) {
            client.close()
            return null
        }
        client.use { socket ->
            socket.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_LENGTH) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size != 3 || parts[0] != "GET" || !parts[2].startsWith("HTTP/1.")) {
                writeResponse(socket, 405, "Method Not Allowed")
                return null
            }
            if (!drainHeaders(input)) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }

            val requestTarget = parts[1]
            val path = requestTarget.substringBefore('?')
            if (path != CALLBACK_PATH) {
                writeResponse(socket, 404, "Not Found")
                return null
            }
            val query = requestTarget.substringAfter('?', "")
            if (query.length > MAX_QUERY_LENGTH) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            val params = parseQuery(query) ?: run {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            val state = params["state"].orEmpty()
            if (state != expectedStateValue) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            val code = params["code"]?.takeIf(String::isNotBlank)
            val error = params["error"]?.takeIf(String::isNotBlank)
            if ((code == null) == (error == null)) {
                writeResponse(socket, 400, "Bad Request")
                return null
            }
            writeResponse(socket, 200, "OK")
            return Callback(state = state, code = code, error = error)
        }
    }

    private fun drainHeaders(input: InputStream): Boolean {
        var total = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_LINE_LENGTH) ?: return false
            total += line.length + 2
            if (total > MAX_HEADER_LENGTH) throw RequestLimitExceededException()
            if (line.isEmpty()) return true
        }
    }

    private fun readAsciiLine(input: InputStream, maxLength: Int): String? {
        val bytes = ByteArrayOutputStream(minOf(maxLength, 256))
        while (true) {
            val value = input.read()
            if (value < 0) return bytes.takeIf { it.size() > 0 }?.toString(Charsets.US_ASCII.name())
            if (value == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            if (bytes.size() >= maxLength) throw RequestLimitExceededException()
            bytes.write(value)
        }
    }

    private fun writeAndClose(socket: Socket, status: Int, reason: String) {
        try {
            writeResponse(socket, status, reason)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun writeResponse(socket: Socket, status: Int, reason: String) {
        runCatching {
            val body = if (status == 200) {
                "<html><body><h3>You can close this window now.</h3></body></html>"
            } else {
                reason
            }
            socket.getOutputStream().use { out ->
                out.write(
                    ("HTTP/1.1 $status $reason\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n" +
                        body).toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    /** 重复参数会产生歧义，整个当前请求拒绝。 */
    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            val index = pair.indexOf('=')
            if (index < 0) return null
            val key = decode(pair.substring(0, index)) ?: return null
            if (key in result) return null
            result[key] = decode(pair.substring(index + 1)) ?: return null
        }
        return result
    }

    private fun decode(value: String): String? = try {
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val CALLBACK_PATH = "/callback"
        const val MAX_QUERY_LENGTH = 4096
        const val MAX_REQUEST_LINE_LENGTH = 8192
        const val MAX_HEADER_LINE_LENGTH = 4096
        const val MAX_HEADER_LENGTH = 16 * 1024
        private const val CLIENT_READ_TIMEOUT_MS = 2_000
    }
}

private class RequestLimitExceededException : IllegalArgumentException()

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error != null) {
                continuation.resumeWithException(error)
            } else {
                continuation.resume(value)
            }
        }
        continuation.invokeOnCancellation { cancel(false) }
    }

/** 使用 Chrome Custom Tabs 打开授权 URL。 */
fun launchOAuthAuthorization(context: Context, authorizationUrl: String) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.launchUrl(context, authorizationUrl.toUri())
}
