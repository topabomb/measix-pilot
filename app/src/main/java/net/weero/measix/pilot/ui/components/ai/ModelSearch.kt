package net.weero.measix.pilot.ui.components.ai

import me.rerere.ai.provider.Model

private val modelSearchWhitespace = Regex("\\s+")

internal fun modelSearchTerms(query: String): List<String> =
    query.trim().split(modelSearchWhitespace).filter(String::isNotEmpty)

internal fun Model.matchesSearchTerms(terms: List<String>): Boolean =
    terms.all { modelId.contains(it, ignoreCase = true) || displayName.contains(it, ignoreCase = true) }
