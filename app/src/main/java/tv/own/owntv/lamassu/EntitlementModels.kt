package tv.own.owntv.lamassu

data class EntitlementPermissions(
    val allowLive: Boolean = true,
    val allowMovies: Boolean = true,
    val allowSeries: Boolean = true,
    val liveCategoryAllowlist: List<String> = emptyList(),
    val liveStreamAllowlist: List<String> = emptyList(),
    val movieCategoryAllowlist: List<String> = emptyList(),
    val movieStreamAllowlist: List<String> = emptyList(),
    val seriesCategoryAllowlist: List<String> = emptyList(),
    val seriesStreamAllowlist: List<String> = emptyList(),
)

data class ManagedProviderCredentials(
    val id: String,
    val name: String,
    val host: String,
    val username: String,
    val password: String,
    val port: Int? = null,
    val useHttps: Boolean = false,
)

fun resolveProviderUseHttps(host: String, explicit: Boolean?, hasExplicit: Boolean): Boolean {
    if (hasExplicit && explicit != null) return explicit
    val h = host.trim()
    if (h.startsWith("https://", ignoreCase = true)) return true
    if (h.startsWith("http://", ignoreCase = true)) return false
    if (Regex(":443(/|$)").containsMatchIn(h)) return true
    if (Regex(":80(/|$)").containsMatchIn(h) || h.contains(":8080") || h.contains(":25461")) {
        return false
    }
    return false
}

fun ManagedProviderCredentials.serverUrl(): String {
    var h = host.trim().removeSuffix("/")
    if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) {
        h = if (useHttps) "https://$h" else "http://$h"
    }
    if (port != null && port > 0 && !Regex(":\\d+(/|$)").containsMatchIn(h)) {
        h = "$h:$port"
    }
    return h
}

data class EntitlementSettings(
    val telegramUsername: String = "@alnafawa",
    val telegramUrl: String = "https://t.me/alnafawa",
    val gracePeriodHours: Int = 72,
    val managedOnlyProviders: Boolean = true,
    val bannerInterval: Int = 5,
)

data class ContentOverlayDto(
    val contentType: String,
    val externalId: String,
    val customTitle: String = "",
    val customLogoUrl: String = "",
    val sortOrder: Int? = null,
    val visibility: String = "both",
    val locked: Boolean = false,
    val lockedForFree: Boolean = false,
    val streamUrl: String = "",
    val categoryExternalId: String? = null,
    val containerExtension: String = "",
)

data class EntitlementSnapshot(
    val mode: String,
    val status: String,
    val codeId: String? = null,
    val codePrefix: String? = null,
    val activatedAt: String? = null,
    val expiresAt: String? = null,
    val maxDevices: Int = 0,
    val usedDevices: Int = 0,
    val remainingDevices: Int = 0,
    val permissions: EntitlementPermissions = EntitlementPermissions(),
    val overlays: List<ContentOverlayDto> = emptyList(),
    val provider: ManagedProviderCredentials? = null,
    val settings: EntitlementSettings = EntitlementSettings(),
    val fetchedAtMs: Long = 0L,
    val messageAr: String? = null,
)

data class SubscriptionPricePlan(
    val key: String,
    val months: Int,
    val labelAr: String,
    val labelEn: String,
    val usd: Double,
    val iqd: Long,
)

data class RemoteAppConfig(
    val appName: String = "Lamassu",
    val maintenanceMode: Boolean = false,
    val freeModeEnabled: Boolean = true,
    val subscriptionEnabled: Boolean = true,
    val telegramUsername: String = "@alnafawa",
    val telegramUrl: String = "https://t.me/alnafawa",
    val announcement: String = "",
    val bannerInterval: Int = 5,
    val gracePeriodHours: Int = 72,
    val managedOnlyProviders: Boolean = true,
    val subscriptionPrices: List<SubscriptionPricePlan> = emptyList(),
)

sealed class EntitlementGateState {
    data object Checking : EntitlementGateState()
    data object NeedsActivation : EntitlementGateState()
    data object Allowed : EntitlementGateState()
    data class Expired(val messageAr: String) : EntitlementGateState()
    data class Maintenance(val messageAr: String) : EntitlementGateState()
    data class ConnectionGrace(val snapshot: EntitlementSnapshot) : EntitlementGateState()
}

data class EntitlementAccess(
    val mode: String = "none",
    val allowLive: Boolean = true,
    val allowMovies: Boolean = true,
    val allowSeries: Boolean = true,
    val managedOnlyProviders: Boolean = false,
) {
    companion object {
        fun open() = EntitlementAccess(mode = "subscription")
        fun none() = EntitlementAccess(
            mode = "none",
            allowLive = false,
            allowMovies = false,
            allowSeries = false,
        )
    }
}
