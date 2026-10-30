package tv.own.owntv.lamassu

object CustomerFacingText {
    private val banned = Regex(
        listOf(
            Regex.escape("cpanel"),
            Regex.escape("netlify"),
            Regex.escape("supabase"),
            Regex.escape("streamvault"),
            Regex.escape("lamassucpanel"),
            Regex.escape("pszvbcbhbchvtorzzzgg"),
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
    private val urls = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    fun sanitize(raw: String?, fallback: String = "Could not reach the server"): String {
        var text = raw?.trim().orEmpty()
        if (text.isBlank()) return fallback
        text = urls.replace(text, "")
        text = banned.replace(text, "")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text.ifBlank { fallback }
    }
}
