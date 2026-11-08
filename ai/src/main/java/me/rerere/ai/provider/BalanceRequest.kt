package me.rerere.ai.provider

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecodingException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.util.json
import me.rerere.common.http.isJsonExprValid
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class InvalidBalanceOption(
    val field: Field,
    detail: String,
) : IllegalArgumentException(detail) {
    enum class Field { URL, RESULT_PATH, BODY }
}

fun ProviderSetting.OpenAI.validateBalanceOption() {
    if (balanceOption.enabled) prepareBalanceRequest("")
}

internal class BalanceRequest(val url: HttpUrl, val jsonBody: String?)

internal fun ProviderSetting.OpenAI.prepareBalanceRequest(key: String): BalanceRequest {
    val option = balanceOption
    val path = option.apiPath
    val absolute = path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)
    val url = (if (absolute) path else baseUrl + path).toHttpUrlOrNull()
        ?: throw invalidBalanceUrl()
    if (!absolute) {
        val base = baseUrl.toHttpUrlOrNull() ?: throw invalidBalanceUrl()
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.encodedUsername != base.encodedUsername || url.encodedPassword != base.encodedPassword
        ) throw invalidBalanceUrl()
    }
    if (!isJsonExprValid(option.resultPath)) {
        throw InvalidBalanceOption(InvalidBalanceOption.Field.RESULT_PATH, "balance_result_path_invalid: Enter a valid JSON expression.")
    }
    if (option.method == BalanceMethod.GET) return BalanceRequest(url, null)

    val body = try {
        json.parseToJsonElement(option.body.ifBlank { "{}" })
    } catch (error: SerializationException) {
        val offset = (error as? JsonDecodingException)?.offset?.takeIf { it >= 0 }
        val position = offset?.let { " at offset $it" }.orEmpty()
        // Parser messages and hints can include values from the draft, including credentials.
        throw InvalidBalanceOption(
            InvalidBalanceOption.Field.BODY,
            "balance_body_invalid_json: Enter a valid JSON object (${error.javaClass.simpleName}$position).",
        )
    }
    if (body !is JsonObject) {
        throw InvalidBalanceOption(InvalidBalanceOption.Field.BODY, "balance_body_requires_object: Enter a JSON object.")
    }
    return BalanceRequest(url, expandBalanceTemplate(body, key, baseUrl).toString())
}

private fun invalidBalanceUrl() = InvalidBalanceOption(
    InvalidBalanceOption.Field.URL,
    "balance_url_invalid: Enter an HTTP(S) URL or a path on the provider base URL.",
)

private val balanceTemplateVariable = Regex("""\{\{([^{}]*)\}\}""")
private val jsonNumber = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

private fun expandBalanceTemplate(value: JsonElement, key: String, baseUrl: String): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (name, element) ->
        if (balanceTemplateVariable.containsMatchIn(name)) {
            throw InvalidBalanceOption(InvalidBalanceOption.Field.BODY, "balance_body_template_key: Use variables in string values only.")
        }
        expandBalanceTemplate(element, key, baseUrl)
    })
    is JsonArray -> JsonArray(value.map { expandBalanceTemplate(it, key, baseUrl) })
    JsonNull -> JsonNull
    is JsonPrimitive -> if (value.isString) {
        JsonPrimitive(balanceTemplateVariable.replace(value.content) { match ->
            when (match.groupValues[1]) {
                "apiKey" -> key
                "baseUrl" -> baseUrl
                else -> throw InvalidBalanceOption(
                    InvalidBalanceOption.Field.BODY,
                    "balance_body_unknown_variable: Only apiKey and baseUrl may appear as template variables in JSON string values.",
                )
            }
        })
    } else {
        if (value.content !in setOf("true", "false") && !jsonNumber.matches(value.content)) {
            throw InvalidBalanceOption(InvalidBalanceOption.Field.BODY, "balance_body_invalid_literal: Quote JSON string values.")
        }
        value
    }
}
