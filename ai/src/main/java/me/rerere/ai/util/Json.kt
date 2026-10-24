package me.rerere.ai.util

import kotlinx.serialization.json.Json

@PublishedApi
internal val json = Json {
    exceptionsWithDebugInfo = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
