package tv.own.owntv.lamassu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                .put("allow_series", s.permissions.allowSeries),
        )
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
            ),
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

    companion object {
        private const val PREFS = "lamassu_entitlement_cache"
        private const val KEY = "snapshot"
    }
}
