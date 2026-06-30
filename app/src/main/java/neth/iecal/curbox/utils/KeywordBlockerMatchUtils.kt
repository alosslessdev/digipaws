package neth.iecal.curbox.utils

import java.net.URI
import java.util.Locale

object KeywordBlockerMatchUtils {

    private val wordSplitRegex = Regex("[^a-zA-Z0-9]+")

    data class ParsedInput(
        val normalizedInput: String,
        val exactCandidates: Set<String>
    )

    fun normalizeBlockedEntry(input: String): String {
        var normalized = input.trim().lowercase(Locale.ROOT)
        normalized = normalized.removePrefix("https://").removePrefix("http://")
        normalized = normalized.removePrefix("www.")
        return normalized.trimEnd('/')
    }

    fun parseInputForMatching(input: String): ParsedInput {
        val normalizedInput = normalizeBlockedEntry(input)
        if (normalizedInput.isBlank()) {
            return ParsedInput("", emptySet())
        }

        val exactCandidates = linkedSetOf<String>()
        exactCandidates.add(normalizedInput)
        exactCandidates.addAll(extractWords(normalizedInput))

        val uri = parseUri(normalizedInput) ?: return ParsedInput(normalizedInput, exactCandidates)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: return ParsedInput(normalizedInput, exactCandidates)

        exactCandidates.add(host)

        val normalizedUrl = buildString {
            append(host)

            val path = uri.rawPath?.trimEnd('/')
            if (!path.isNullOrEmpty() && path != "/") {
                append(path)
            }

            val query = uri.rawQuery
            if (!query.isNullOrEmpty()) {
                append('?')
                append(query)
            }
        }

        exactCandidates.add(normalizedUrl)

        val hostParts = host.split(".").filter { it.isNotBlank() }
        if (hostParts.size >= 2) {
            exactCandidates.add(hostParts[hostParts.lastIndex - 1])
        }

        uri.path?.let { exactCandidates.addAll(extractWords(it)) }
        uri.query?.split("&")?.forEach { param ->
            val parts = param.split("=", limit = 2)
            exactCandidates.addAll(extractWords(parts[0]))
            parts.getOrNull(1)?.let { extractWords(it) }
        }

        return ParsedInput(normalizedInput, exactCandidates)
    }

    fun findBlockedEntry(
        input: String,
        blockedEntries: List<String>,
        allowSubstringMatch: Boolean
    ): String? {
        if (blockedEntries.isEmpty()) return null

        val parsedInput = parseInputForMatching(input)
        if (parsedInput.normalizedInput.isBlank()) return null

        // Log.d is not available here easily without context, but this is a utility.
        // We'll rely on the logging in KeywordBlocker.

        blockedEntries.firstOrNull { it in parsedInput.exactCandidates }?.let { return it }

        if (!allowSubstringMatch) return null

        return blockedEntries.firstOrNull { blockedEntry ->
            blockedEntry.isNotBlank() && parsedInput.exactCandidates.any { candidate ->
                candidate.contains(blockedEntry) ||
                    (isUrlLike(candidate) && isUrlLike(blockedEntry) && blockedEntry.contains(candidate))
            }
        }
    }

    private fun parseUri(input: String): URI? {
        val candidate = if ("://" in input) input else "https://$input"
        return try {
            URI(candidate)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractWords(text: String): Set<String> {
        return text.split(wordSplitRegex)
            .filter { it.isNotBlank() }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
    }

    private fun isUrlLike(value: String): Boolean {
        return "." in value || "/" in value || "?" in value
    }
}
