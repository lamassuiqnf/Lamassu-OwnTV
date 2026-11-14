package tv.own.owntv.lamassu

/**
 * CPanel export ids sometimes carry a media prefix (`live:12`, `movie:9`, `series:3`).
 * Strip that so they match OwnTV Xtream [remoteId] values.
 */
internal object LamassuExportIds {
    const val NONE = "__none__"

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("live:") ||
                lower.startsWith("movie:") ||
                lower.startsWith("series:") -> trimmed.substringAfter(':').trim()
            else -> trimmed
        }
    }

    fun normalizeSet(raw: Collection<String>): Set<String> =
        raw.mapNotNull { id ->
            normalize(id).takeIf { it.isNotBlank() && it != NONE }
        }.toSet()

    fun hasNone(raw: Collection<String>): Boolean =
        raw.any { it.trim() == NONE }
}
