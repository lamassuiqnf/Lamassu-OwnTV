package tv.own.owntv.lamassu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class EntitlementCacheStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun get(): EntitlementSnapshot? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY, null) ?: return@withContext null
        runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    suspend fun set(snapshot: EntitlementSnapshot) = withContext(Dispatchers.IO) {
        val safe = snapshot.copy(provider = snapshot.provider?.copy(password = ""))
        prefs.edit().putString(KEY, encode(safe).toString()).apply()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY).apply()
    }

    private fun encode(s: EntitlementSnapshot): JSONObject = JSONObject()
        .put("mode", s.mode)
        .put("status", s.status)
        .put("code_id", s.codeId)
        .put("code_prefix", s.codePrefix)
        .put("activated_at", s.activatedAt)
        .put("expires_at", s.expiresAt)
        .put("max_devices", s.maxDevices)
        .put("used_devices", s.usedDevices)
        .put("remaining_devices", s.remainingDevices)
        .put(
            "permissions",
            JSONObject()
                .put("allow_live", s.permissions.allowLive)
                .put("allow_movies", s.permissions.allowMovies)
                .put("allow_series", s.permissions.allowSeries)
                .put("live_category_allowlist", JSONArray(s.permissions.liveCategoryAllowlist))
                .put("live_stream_allowlist", JSONArray(s.permissions.liveStreamAllowlist))
                .put("movie_category_allowlist", JSONArray(s.permissions.movieCategoryAllowlist))
                .put("movie_stream_allowlist", JSONArray(s.permissions.movieStreamAllowlist))
                .put("series_category_allowlist", JSONArray(s.permissions.seriesCategoryAllowlist))
                .put("series_stream_allowlist", JSONArray(s.permissions.seriesStreamAllowlist)),
        )
        .put("overlays", encodeOverlays(s.overlays))
        .put(
            "provider",
            s.provider?.let {
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("host", it.host)
                    .put("username", it.username)
                    .put("password", it.password)
                    .put("port", it.port)
                    .put("use_https", it.useHttps)
            },
        )
        .put(
            "settings",
            JSONObject()
                .put("telegram_username", s.settings.telegramUsername)
                .put("telegram_url", s.settings.telegramUrl)
                .put("grace_period_hours", s.settings.gracePeriodHours)
                .put("managed_only_providers", s.settings.managedOnlyProviders)
                .put("banner_interval", s.settings.bannerInterval),
        )
        .put("fetched_at_ms", s.fetchedAtMs)

    private fun encodeOverlays(overlays: List<ContentOverlayDto>): JSONArray {
        val arr = JSONArray()
        overlays.forEach { o ->
            arr.put(
                JSONObject()
                    .put("content_type", o.contentType)
                    .put("external_id", o.externalId)
                    .put("custom_title", o.customTitle)
                    .put("custom_logo_url", o.customLogoUrl)
                    .put("sort_order", o.sortOrder)
                    .put("visibility", o.visibility)
                    .put("locked", o.locked)
                    .put("locked_for_free", o.lockedForFree)
                    .put("stream_url", o.streamUrl)
                    .put("category_external_id", o.categoryExternalId)
                    .put("container_extension", o.containerExtension),
            )
        }
        return arr
    }

    private fun decode(json: JSONObject): EntitlementSnapshot {
        val perms = json.optJSONObject("permissions")
        val provider = json.optJSONObject("provider")
        val settings = json.optJSONObject("settings")
        return EntitlementSnapshot(
            mode = json.optString("mode", "none"),
            status = json.optString("status", "none"),
            codeId = json.optString("code_id").takeIf { it.isNotBlank() },
            codePrefix = json.optString("code_prefix").takeIf { it.isNotBlank() },
            activatedAt = json.optString("activated_at").takeIf { it.isNotBlank() },
            expiresAt = json.optString("expires_at").takeIf { it.isNotBlank() },
            maxDevices = json.optInt("max_devices", 0),
            usedDevices = json.optInt("used_devices", 0),
            remainingDevices = json.optInt("remaining_devices", 0),
            permissions = EntitlementPermissions(
                allowLive = perms?.optBoolean("allow_live", true) ?: true,
                allowMovies = perms?.optBoolean("allow_movies", true) ?: true,
                allowSeries = perms?.optBoolean("allow_series", true) ?: true,
                liveCategoryAllowlist = perms.stringList("live_category_allowlist"),
                liveStreamAllowlist = perms.stringList("live_stream_allowlist"),
                movieCategoryAllowlist = perms.stringList("movie_category_allowlist"),
                movieStreamAllowlist = perms.stringList("movie_stream_allowlist"),
                seriesCategoryAllowlist = perms.stringList("series_category_allowlist"),
                seriesStreamAllowlist = perms.stringList("series_stream_allowlist"),
            ),
            overlays = decodeOverlays(json.optJSONArray("overlays")),
            provider = provider?.takeIf { it.has("host") }?.let {
                val host = it.optString("host")
                ManagedProviderCredentials(
                    id = it.optString("id"),
                    name = it.optString("name", "Lamassu"),
                    host = host,
                    username = it.optString("username"),
                    password = it.optString("password"),
                    port = it.optInt("port").takeIf { p -> p > 0 },
                    useHttps = resolveProviderUseHttps(
                        host,
                        if (it.has("use_https")) it.optBoolean("use_https") else null,
                        it.has("use_https"),
                    ),
                )
            },
            settings = EntitlementSettings(
                telegramUsername = settings?.optString("telegram_username", "@alnafawa") ?: "@alnafawa",
                telegramUrl = settings?.optString("telegram_url", "https://t.me/alnafawa")
                    ?: "https://t.me/alnafawa",
                gracePeriodHours = settings?.optInt("grace_period_hours", 72) ?: 72,
                managedOnlyProviders = settings?.optBoolean("managed_only_providers", true) ?: true,
                bannerInterval = settings?.optInt("banner_interval", 5) ?: 5,
            ),
            fetchedAtMs = json.optLong("fetched_at_ms", 0L),
        )
    }

    private fun decodeOverlays(arr: JSONArray?): List<ContentOverlayDto> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optString("content_type").trim()
                val ext = item.optString("external_id").trim()
                if (type.isBlank() || ext.isBlank()) continue
                add(
                    ContentOverlayDto(
                        contentType = type,
                        externalId = ext,
                        customTitle = item.optString("custom_title"),
                        customLogoUrl = item.optString("custom_logo_url"),
                        sortOrder = if (item.has("sort_order") && !item.isNull("sort_order")) {
                            item.optInt("sort_order")
                        } else {
                            null
                        },
                        visibility = item.optString("visibility", "both").ifBlank { "both" },
                        locked = item.optBoolean("locked", false),
                        lockedForFree = item.optBoolean("locked_for_free", false),
                        streamUrl = item.optString("stream_url"),
                        categoryExternalId = item.optString("category_external_id")
                            .takeIf { it.isNotBlank() },
                        containerExtension = item.optString("container_extension"),
                    ),
                )
            }
        }
    }

    /** Number-safe allowlist decode — mirrors AnotherIPTV / [EntitlementApiClient]. */
    private fun JSONObject?.stringList(key: String): List<String> {
        if (this == null || !has(key)) return emptyList()
        val arr = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val raw = arr.opt(i) ?: continue
                val v = when (raw) {
                    is String -> raw.trim()
                    is Number -> raw.toString()
                    else -> raw.toString().trim()
                }
                if (v.isNotBlank() && v != "null") add(v)
            }
        }
    }

    companion object {
        private const val PREFS = "lamassu_entitlement_cache"
        private const val KEY = "snapshot"
    }
}
