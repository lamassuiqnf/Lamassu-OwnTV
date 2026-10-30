package tv.own.owntv.lamassu

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tv.own.owntv.BuildConfig

class EntitlementApiClient(okHttpClient: OkHttpClient) {
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() = BuildConfig.LAMASSU_API_BASE_URL.trim().trimEnd('/')

    suspend fun fetchConfig(): ApiResult<RemoteAppConfig> = withContext(Dispatchers.IO) {
        getJson(cacheBusted("$baseUrl/api/v1/config")).mapCatching { json ->
            RemoteAppConfig(
                appName = json.optString("app_name", "Lamassu"),
                maintenanceMode = json.optBoolean("maintenance_mode", false),
                freeModeEnabled = json.optBoolean("free_mode_enabled", true),
                subscriptionEnabled = json.optBoolean("subscription_enabled", true),
                telegramUsername = json.optString("telegram_username", "@alnafawa"),
                telegramUrl = json.optString("telegram_url", "https://t.me/alnafawa"),
                announcement = json.optString("announcement", ""),
                bannerInterval = json.optInt("banner_interval", 5),
                gracePeriodHours = json.optInt("grace_period_hours", 72),
                managedOnlyProviders = json.optBoolean("managed_only_providers", true),
                subscriptionPrices = parseSubscriptionPrices(json.optJSONObject("subscription_prices")),
            )
        }
    }

    suspend fun activateFree(installId: String, deviceFingerprint: String): ApiResult<EntitlementSnapshot> =
        postEntitlement(
            "$baseUrl/api/v1/subscription/activate",
            JSONObject()
                .put("mode", "free")
                .put("install_id", installId)
                .put("device_fingerprint", deviceFingerprint)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("app_version_name", BuildConfig.VERSION_NAME),
            installId,
        )

    suspend fun activateCode(
        installId: String,
        code: String,
        deviceFingerprint: String,
    ): ApiResult<EntitlementSnapshot> =
        postEntitlement(
            "$baseUrl/api/v1/subscription/activate",
            JSONObject()
                .put("mode", "subscription")
                .put("install_id", installId)
                .put("device_fingerprint", deviceFingerprint)
                .put("code", code.trim())
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("app_version_name", BuildConfig.VERSION_NAME),
            installId,
        )

    suspend fun validate(installId: String, snapshot: EntitlementSnapshot): ApiResult<EntitlementSnapshot> =
        postEntitlement(
            "$baseUrl/api/v1/subscription/validate",
            JSONObject()
                .put("mode", snapshot.mode)
                .put("install_id", installId)
                .put("code_id", snapshot.codeId ?: ""),
            installId,
        )

    suspend fun restoreByDevice(
        installId: String,
        deviceFingerprint: String,
    ): ApiResult<EntitlementSnapshot> =
        postEntitlement(
            "$baseUrl/api/v1/subscription/restore",
            JSONObject()
                .put("install_id", installId)
                .put("device_fingerprint", deviceFingerprint)
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("app_version_name", BuildConfig.VERSION_NAME),
            installId,
        )

    private suspend fun postEntitlement(
        url: String,
        body: JSONObject,
        installId: String,
    ): ApiResult<EntitlementSnapshot> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Cache-Control", "no-cache")
                .header("X-Install-Id", installId)
                .header("User-Agent", "Lamassu-OwnTV/${BuildConfig.VERSION_NAME}")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = if (text.isNotBlank()) JSONObject(text) else JSONObject()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error(
                        CustomerFacingText.sanitize(
                            json.optString("message_ar").ifBlank {
                                json.optString("error", "Could not reach the server")
                            },
                        ),
                    )
                }
                ApiResult.Success(parseSnapshot(json))
            }
        } catch (e: Exception) {
            ApiResult.Error(CustomerFacingText.sanitize(e.message), e)
        }
    }

    private fun getJson(url: String): ApiResult<JSONObject> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "Lamassu-OwnTV/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return ApiResult.Error("HTTP ${response.code}")
                ApiResult.Success(JSONObject(text))
            }
        } catch (e: Exception) {
            ApiResult.Error(CustomerFacingText.sanitize(e.message), e)
        }
    }

    private fun cacheBusted(url: String): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}_=${System.currentTimeMillis()}"
    }

    private fun <T> ApiResult<JSONObject>.mapCatching(block: (JSONObject) -> T): ApiResult<T> = when (this) {
        is ApiResult.Success -> try {
            ApiResult.Success(block(data))
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "parse", e)
        }
        is ApiResult.Error -> ApiResult.Error(message, exception)
    }

    private fun parseSubscriptionPrices(obj: JSONObject?): List<SubscriptionPricePlan> {
        val arr = obj?.optJSONArray("plans") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                add(
                    SubscriptionPricePlan(
                        key = p.optString("key", ""),
                        months = p.optInt("months", 0),
                        labelAr = p.optString("label_ar", ""),
                        labelEn = p.optString("label_en", ""),
                        usd = p.optDouble("usd", 0.0),
                        iqd = p.optLong("iqd", 0L),
                    ),
                )
            }
        }
    }

    private fun parseSnapshot(json: JSONObject): EntitlementSnapshot {
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
            overlays = parseOverlays(json.optJSONArray("overlays")),
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
            fetchedAtMs = System.currentTimeMillis(),
            messageAr = json.optString("message_ar").takeIf { it.isNotBlank() },
        )
    }

    private fun parseOverlays(arr: org.json.JSONArray?): List<ContentOverlayDto> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optString("content_type").trim()
                val ext = item.optString("external_id").trim()
                if (type.isBlank() || ext.isBlank()) continue
                val visibility = item.optString("visibility", "both").ifBlank { "both" }
                val lockedForFree = item.optBoolean("locked_for_free", false) ||
                    item.optBoolean("locked", false) ||
                    visibility.equals("subscription", ignoreCase = true)
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
                        visibility = visibility,
                        locked = item.optBoolean("locked", lockedForFree),
                        lockedForFree = lockedForFree,
                        streamUrl = item.optString("stream_url"),
                        categoryExternalId = item.optString("category_external_id")
                            .ifBlank { item.optString("meta_category_id") }
                            .takeIf { it.isNotBlank() },
                        containerExtension = item.optString("container_extension"),
                    ),
                )
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
