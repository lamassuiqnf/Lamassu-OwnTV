package tv.own.owntv.lamassu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CPanel content overlays keyed as `contentType:externalId` (e.g. `movie:123`).
 * Used to rename/relabel catalog rows and gate free-mode locks to match the panel export.
 */
class ContentOverlayStore {
    private val _overlays = MutableStateFlow<Map<String, ContentOverlayDto>>(emptyMap())
    val overlays: StateFlow<Map<String, ContentOverlayDto>> = _overlays.asStateFlow()

    private val _mode = MutableStateFlow("none")
    val entitlementMode: StateFlow<String> = _mode.asStateFlow()

    fun replaceAll(overlays: List<ContentOverlayDto>, mode: String) {
        _mode.value = mode
        _overlays.value = overlays.associateBy { key(it.contentType, it.externalId) }
    }

    fun find(contentType: String, remoteId: String?): ContentOverlayDto? {
        val id = remoteId?.trim().orEmpty()
        if (id.isEmpty()) return null
        val type = contentType.trim().lowercase()
        return _overlays.value[key(type, id)]
            ?: _overlays.value[key(type, id.trimStart('0').ifEmpty { "0" })]
    }

    fun displayTitle(contentType: String, remoteId: String?, fallback: String): String {
        val custom = find(contentType, remoteId)?.customTitle?.trim().orEmpty()
        return custom.ifBlank { fallback }
    }

    fun displayLogo(contentType: String, remoteId: String?, fallback: String?): String? {
        val custom = find(contentType, remoteId)?.customLogoUrl?.trim().orEmpty()
        return custom.ifBlank { fallback }
    }

    fun isVisible(contentType: String, remoteId: String?): Boolean {
        val overlay = find(contentType, remoteId) ?: return true
        val mode = _mode.value.lowercase()
        return when (overlay.visibility.lowercase()) {
            "hidden", "none" -> false
            "subscription" -> mode == "subscription"
            "free" -> mode == "free" || mode == "subscription"
            else -> true
        }
    }

    fun isLockedForFree(contentType: String, remoteId: String?): Boolean {
        val overlay = find(contentType, remoteId) ?: return false
        if (_mode.value.equals("subscription", ignoreCase = true)) return false
        return overlay.lockedForFree || overlay.locked ||
            overlay.visibility.equals("subscription", ignoreCase = true)
    }

    companion object {
        fun key(contentType: String, externalId: String): String =
            "${contentType.trim().lowercase()}:${externalId.trim()}"
    }
}
