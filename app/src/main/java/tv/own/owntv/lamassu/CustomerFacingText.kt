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
            Regex.escape("xtream"),
            Regex.escape("player_api"),
            Regex.escape("get.php"),
            Regex.escape("xmltv.php"),
            Regex.escape("panel_api"),
            Regex.escape("stalker"),
            Regex.escape("portal.php"),
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
    private val urls = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val credentialPairs = Regex(
        """(?i)\b(?:password|passwd|pass|pwd|username|user|login|host|server|url|token|mac|device_id)\b\s*[:=]\s*[^\s,;|/]+""",
    )
    private val hostLike = Regex(
        """(?i)\b(?:(?:\d{1,3}\.){3}\d{1,3}|(?:[a-z0-9-]+\.)+(?:com|net|org|io|tv|xyz|online|live|iptv|info|cc|me|app|dev|cloud|site))(?::\d{2,5})?\b""",
    )
    private val userPassInPath = Regex("""(?i)/(?:live|movie|series)/[^/\s]+/[^/\s]+/""")

    fun sanitize(raw: String?, fallback: String = "Could not reach the server"): String {
        val cleaned = scrub(raw)
        return cleaned ?: fallback
    }

    /** True when [raw] needs no scrubbing (safe to show as-is). */
    fun isAlreadySafe(raw: String?): Boolean {
        val original = raw?.trim().orEmpty()
        if (original.length < 3) return false
        return scrub(original) == original
    }

    private fun scrub(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        text = urls.replace(text, " ")
        text = credentialPairs.replace(text, " ")
        text = userPassInPath.replace(text, " ")
        text = hostLike.replace(text, " ")
        text = banned.replace(text, " ")
        text = text.replace(Regex("""[|/\\]{2,}"""), " ")
        text = text.replace(Regex("""\s+"""), " ").trim(' ', '-', '·', ':', ',', ';')
        if (text.length < 3) return null
        if (text.contains('@') && text.contains('.')) return null
        return text
    }
}
