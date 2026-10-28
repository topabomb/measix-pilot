package net.weero.measix.pilot.data.ai.mcp

import android.util.Log
import me.rerere.common.http.readResponse
import me.rerere.common.http.withSingleAttemptBody
import net.weero.measix.pilot.utils.redactDiagnosticSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64

private const val TAG = "McpOAuthClient"

private val oauthSecretAssignment = Regex(
    """(?i)(["']?(?:client_secret|code_verifier|code)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^"'\s,;&]+)""",
)

private fun redactOAuthDiagnostic(text: String, secrets: List<String> = emptyList()): String {
    val safe = secrets.fold(text) { value, secret ->
        value.replace(secret, "<redacted>")
            .replace(java.net.URLEncoder.encode(secret, "UTF-8"), "<redacted>")
    }
    return oauthSecretAssignment.replace(redactDiagnosticSecrets(safe)) { it.groupValues[1] + "<redacted>" }
}

/** Error bodies and parser excerpts are untrusted; never attach the unsanitized original cause. */
private fun Throwable.oauthDiagnosticCopy(secrets: List<String>): Throwable {
    val copies = java.util.IdentityHashMap<Throwable, Throwable>()
    fun copy(error: Throwable): Throwable {
        copies[error]?.let { return it }
        val message = redactOAuthDiagnostic(error.message.orEmpty(), secrets)
        val diagnostic = if (error is McpOAuthResponseException) {
            McpOAuthResponseException(error.statusCode, error.errorCode?.let { redactOAuthDiagnostic(it, secrets) }, message)
        } else if (error is java.io.IOException) {
            OAuthDiagnosticIOException("${error.javaClass.name}: $message")
        } else {
            OAuthDiagnosticException("${error.javaClass.name}: $message")
        }
        copies[error] = diagnostic
        diagnostic.stackTrace = error.stackTrace
        error.cause?.takeUnless { it === error }?.let { diagnostic.initCause(copy(it)) }
        error.suppressed.filterNot { it === error }.forEach { diagnostic.addSuppressed(copy(it)) }
        return diagnostic
    }
    return copy(this)
}

private class OAuthDiagnosticException(message: String) : Exception(message) {
    override fun toString(): String = requireNotNull(message)
}

private class OAuthDiagnosticIOException(message: String) : java.io.IOException(message) {
    override fun toString(): String = requireNotNull(message)
}

private suspend fun <T> oauthOperation(vararg secrets: String?, block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw error.oauthDiagnosticCopy(secrets.filterNotNull().filter(String::isNotEmpty))
        }
    }

internal class McpOAuthResponseException(
    val statusCode: Int,
    val errorCode: String? = null,
    message: String,
) : Exception(message)

/**
 * MCP OAuth 2.1 授权客户端，实现规范 (2025-11-25 basic/authorization) 所需的各环节：
 *
 * - RFC 9728 受保护资源元数据发现
 * - RFC 8414 / OIDC 授权服务器元数据发现
 * - RFC 7591 动态客户端注册 (DCR)
 * - 带 PKCE (S256) 的授权码流程
 * - RFC 8707 Resource Indicators
 * - 令牌刷新
 *
 * SDK (kotlin-sdk) 本身不提供 OAuth 支持，因此该逻辑完全独立实现，
 * 最终仅通过 transport 的 requestBuilder 注入 `Authorization: Bearer` 请求头。
 */
class McpOAuthClient(
    private val httpClient: OkHttpClient,
) {
    private val singleAttemptClient = httpClient.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE).build()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class ProtectedResourceMetadata(
        val resource: String? = null,
        @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    )

    @Serializable
    data class AuthorizationServerMetadata(
        val issuer: String? = null,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
        @SerialName("token_endpoint") val tokenEndpoint: String? = null,
        @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
        @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
    )

    @Serializable
    private data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
        @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
        @SerialName("scope") val scope: String? = null,
    )

    @Serializable
    data class ClientRegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )

    /** OAuth 2.0 标准错误响应 (RFC 6749 §5.2)。 */
    @Serializable
    data class OAuthErrorResponse(
        val error: String,
        @SerialName("error_description") val errorDescription: String? = null,
        @SerialName("error_uri") val errorUri: String? = null,
    )

    /** PKCE 参数对 (code_verifier / code_challenge)。 */
    data class Pkce(val verifier: String, val challenge: String)

    // ---------------------------------------------------------------------
    // 元数据发现
    // ---------------------------------------------------------------------

    /**
     * 发现受保护资源元数据 (RFC 9728)。优先根据服务器 401 响应中的
     * `WWW-Authenticate: resource_metadata="..."` 定位，退回到 well-known 路径。
     */
    suspend fun discoverProtectedResource(serverUrl: String): ProtectedResourceMetadata? =
        oauthOperation {
            val candidates = buildList {
                probeResourceMetadataUrl(serverUrl)?.let { add(it) }
                addAll(wellKnownPrmUrls(serverUrl))
            }.distinct()
            for (url in candidates) {
                requireSecureOAuthEndpoint(url, "protected resource metadata endpoint")
                val meta = getMetadata<ProtectedResourceMetadata>(url) ?: continue
                validateProtectedResourceMetadata(canonicalResource(serverUrl), meta)
                Log.i(TAG, redactOAuthDiagnostic("discoverProtectedResource: found via $url"))
                return@oauthOperation meta
            }
            null // Only explicit 404/410 across every candidate means metadata is absent.
        }

    suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata =
        oauthOperation {
            requireSecureOAuthEndpoint(issuer, "authorization server issuer")
            for (url in wellKnownAsUrls(issuer)) {
                requireSecureOAuthEndpoint(url, "authorization server metadata endpoint")
                val meta = getMetadata<AuthorizationServerMetadata>(url) ?: continue
                validateAuthorizationServerMetadata(issuer, meta)
                Log.i(TAG, redactOAuthDiagnostic("discoverAuthorizationServer: found via $url"))
                return@oauthOperation meta
            }
            error("oauth_authorization_metadata_missing: $issuer")
        }

    /** 动态客户端注册 (RFC 7591)，返回 client_id (公共客户端通常无 secret)。 */
    suspend fun registerClient(
        registrationEndpoint: String,
        clientName: String,
        redirectUri: String,
        scope: String?,
    ): ClientRegistrationResponse = oauthOperation {
        requireSecureOAuthEndpoint(registrationEndpoint, "client registration endpoint")
        val body = json.encodeToString(
            ClientRegistrationRequest.serializer(),
            ClientRegistrationRequest(
                clientName = clientName.ifBlank { "MeasixPilot" },
                redirectUris = listOf(redirectUri),
                scope = scope,
            )
        )
        val request = Request.Builder()
            .url(registrationEndpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val text = execute(request)
        json.decodeFromString(ClientRegistrationResponse.serializer(), text)
    }

    // ---------------------------------------------------------------------
    // 授权码流程
    // ---------------------------------------------------------------------

    fun generatePkce(): Pkce {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(verifierBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier = verifier, challenge = base64Url(digest))
    }

    fun generateState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    /** 拼接授权端点 URL，附带 PKCE、state 以及 RFC 8707 resource 参数。 */
    fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        pkce: Pkce,
        state: String,
        scope: String?,
        resource: String?,
    ): String {
        val base = requireSecureOAuthEndpoint(authorizationEndpoint, "authorization endpoint")
        return base.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .apply {
                if (!resource.isNullOrBlank()) addQueryParameter("resource", resource)
                if (!scope.isNullOrBlank()) addQueryParameter("scope", scope)
            }
            .build()
            .toString()
    }

    /** 用授权码换取访问令牌。 */
    suspend fun exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        resource: String?,
    ): TokenResponse = oauthOperation(clientSecret, code, codeVerifier) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .apply {
                if (!resource.isNullOrBlank()) add("resource", resource)
                if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret)
            }
            .build()
        postToken(tokenEndpoint, form)
    }

    /** 使用 refresh_token 刷新访问令牌。 */
    suspend fun refreshToken(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
        resource: String?,
        scope: String?,
    ): TokenResponse = oauthOperation(clientSecret, refreshToken) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .apply {
                if (!resource.isNullOrBlank()) add("resource", resource)
                if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret)
                if (!scope.isNullOrBlank()) add("scope", scope)
            }
            .build()
        postToken(tokenEndpoint, form)
    }

    private suspend fun postToken(tokenEndpoint: String, form: FormBody): TokenResponse {
        requireSecureOAuthEndpoint(tokenEndpoint, "token endpoint")
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        return singleAttemptClient.newCall(request.withSingleAttemptBody()).readResponse { response ->
            val body = response.body.string()
            if (response.isSuccessful) {
                // HTTP 2xx: 先尝试解析为成功响应（有些 AS 用 200 返回错误体）
                val token = runCatching { json.decodeFromString(TokenResponse.serializer(), body) }.getOrNull()
                if (token?.accessToken != null) return@readResponse token
                val err = runCatching { json.decodeFromString(OAuthErrorResponse.serializer(), body) }.getOrNull()
                if (err != null) {
                    throw McpOAuthResponseException(response.code, err.error,
                        "OAuth token error: ${err.error}${err.errorDescription?.let { " - $it" } ?: ""}")
                }
                throw McpOAuthResponseException(response.code, message =
                    "OAuth token response missing access_token: ${redactDiagnosticSecrets(body)}")
            } else {
                // HTTP 4xx/5xx: 解析标准 OAuth 错误响应 (RFC 6749 §5.2)
                val err = runCatching { json.decodeFromString(OAuthErrorResponse.serializer(), body) }.getOrNull()
                if (err != null) {
                    throw McpOAuthResponseException(response.code, err.error,
                        "OAuth token error (${response.code}): ${err.error}${err.errorDescription?.let { " - $it" } ?: ""}")
                }
                throw McpOAuthResponseException(response.code, message =
                    "HTTP ${response.code} for $tokenEndpoint: ${redactDiagnosticSecrets(body)}")
            }
        }
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    /** 向 MCP Server 发一次探测请求，从 401 的 WWW-Authenticate 提取 resource_metadata。 */
    private suspend fun probeResourceMetadataUrl(serverUrl: String): String? {
        val request = Request.Builder()
            .url(serverUrl)
            .header("Accept", "application/json, text/event-stream")
            .get()
            .build()
        return httpClient.newCall(request).readResponse { response ->
            if (response.code == 401) {
                response.header("WWW-Authenticate")?.let(::parseResourceMetadata)
            } else if (response.isSuccessful || response.code in listOf(404, 405)) {
                null
            } else {
                throw McpOAuthResponseException(response.code, message =
                    "HTTP ${response.code} probing ${request.url}: ${redactDiagnosticSecrets(response.body.string())}")
            }
        }
    }

    private fun parseResourceMetadata(wwwAuthenticate: String): String? {
        val regex = Regex("resource_metadata=\"([^\"]+)\"")
        return regex.find(wwwAuthenticate)?.groupValues?.getOrNull(1)
    }

    private fun wellKnownPrmUrls(serverUrl: String): List<String> {
        val url = serverUrl.toHttpUrlOrNull() ?: return emptyList()
        val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        val path = url.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotEmpty() && path != "/") {
                add("$origin/.well-known/oauth-protected-resource$path")
            }
            add("$origin/.well-known/oauth-protected-resource")
        }.distinct()
    }

    private fun wellKnownAsUrls(issuer: String): List<String> {
        val url = issuer.toHttpUrlOrNull() ?: return emptyList()
        val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        val path = url.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotEmpty() && path != "/") {
                add("$origin/.well-known/oauth-authorization-server$path")
                add("$origin/.well-known/openid-configuration$path")
                add("$origin$path/.well-known/openid-configuration")
            }
            add("$origin/.well-known/oauth-authorization-server")
            add("$origin/.well-known/openid-configuration")
        }.distinct()
    }

    private suspend inline fun <reified T> getMetadata(url: String): T? {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return httpClient.newCall(request).readResponse { response ->
            if (response.code == 404 || response.code == 410) return@readResponse null
            val body = response.body.string()
            if (!response.isSuccessful) throw McpOAuthResponseException(response.code, message =
                "HTTP ${response.code} for $url: ${redactDiagnosticSecrets(body)}")
            json.decodeFromString<T>(body)
        }
    }

    private suspend fun execute(request: Request): String =
        singleAttemptClient.newCall(request.withSingleAttemptBody()).readResponse { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw McpOAuthResponseException(response.code, message =
                "HTTP ${response.code} for ${request.url}: ${redactDiagnosticSecrets(body)}")
            body
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun base64Url(bytes: ByteArray): String =
            Base64.UrlSafe.encode(bytes).trimEnd('=')

        /** Root slash equivalence does not change non-root paths, queries or percent escapes. */
        fun canonicalResource(serverUrl: String): String {
            val url = validatedResourceUri(serverUrl)
            val encoded = url.toString()
            return if (url.encodedPath == "/" && url.encodedQuery == null) encoded.removeSuffix("/") else encoded
        }

        /** Missing persisted audience belongs to the released pre-normalization token protocol. */
        internal fun legacyAuthorizedResource(serverUrl: String): String = validatedResourceUri(serverUrl).toString()

        internal fun resourceOrigin(serverUrl: String): String =
            requireSecureOAuthEndpoint(serverUrl, "MCP resource").newBuilder()
                .encodedPath("/").query(null).build().toString().removeSuffix("/")

        internal fun requireSecureOAuthEndpoint(value: String, label: String): HttpUrl {
            val url = value.toHttpUrlOrNull() ?: error(redactOAuthDiagnostic("Invalid $label: $value"))
            require(url.scheme == "https") { redactOAuthDiagnostic("$label must use HTTPS: $value") }
            require(url.fragment == null) { redactOAuthDiagnostic("$label must not contain a fragment: $value") }
            require(url.username.isEmpty() && url.password.isEmpty()) {
                redactOAuthDiagnostic("$label must not contain user information: $value")
            }
            return url
        }

        internal fun validateProtectedResourceMetadata(
            expectedResource: String,
            metadata: ProtectedResourceMetadata,
        ) {
            val declaredResource = metadata.resource
                ?: error("Protected resource metadata is missing resource")
            require(validatedResourceUri(declaredResource) == validatedResourceUri(expectedResource)) {
                "Protected resource metadata does not match the MCP resource"
            }
            require(metadata.authorizationServers.isNotEmpty()) {
                "Protected resource metadata has no authorization server"
            }
            metadata.authorizationServers.forEach { issuer ->
                requireSecureOAuthEndpoint(issuer, "authorization server issuer")
            }
        }

        internal fun validateAuthorizationServerMetadata(
            expectedIssuer: String,
            metadata: AuthorizationServerMetadata,
        ) {
            val declaredIssuer = metadata.issuer
                ?: error("Authorization server metadata is missing issuer")
            val declaredIssuerUrl = requireSecureOAuthEndpoint(
                declaredIssuer,
                "authorization server metadata issuer",
            )
            val expectedIssuerUrl = requireSecureOAuthEndpoint(expectedIssuer, "authorization server issuer")
            require(declaredIssuerUrl == expectedIssuerUrl) {
                "Authorization server metadata issuer mismatch"
            }
            requireSecureOAuthEndpoint(
                metadata.authorizationEndpoint ?: error("Authorization server is missing authorization_endpoint"),
                "authorization endpoint",
            )
            requireSecureOAuthEndpoint(
                metadata.tokenEndpoint ?: error("Authorization server is missing token_endpoint"),
                "token endpoint",
            )
            metadata.registrationEndpoint?.let { endpoint ->
                requireSecureOAuthEndpoint(endpoint, "client registration endpoint")
            }
        }

        private fun validatedResourceUri(value: String): HttpUrl {
            val url = value.toHttpUrlOrNull() ?: error(redactOAuthDiagnostic("Invalid MCP resource URI: $value"))
            require(url.fragment == null) { redactOAuthDiagnostic("MCP resource URI must not contain a fragment: $value") }
            require(url.username.isEmpty() && url.password.isEmpty()) {
                redactOAuthDiagnostic("MCP resource URI must not contain user information: $value")
            }
            return url
        }
    }
}
