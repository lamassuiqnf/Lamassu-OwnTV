package tv.own.owntv.lamassu

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.BuildConfig

/**
 * Fetches CPanel-controlled Home banners + section cards from
 * `GET {LAMASSU_API_BASE_URL}/api/v1/banners`.
 */
class HomeContentRepository(
    okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() = BuildConfig.LAMASSU_API_BASE_URL.trim().trimEnd('/')

    private val _slides = MutableStateFlow<List<HomeSlide>>(emptyList())
    val slides: StateFlow<List<HomeSlide>> = _slides.asStateFlow()

    private val _sections = MutableStateFlow<List<HomeSection>>(emptyList())
    val sections: StateFlow<List<HomeSection>> = _sections.asStateFlow()

    suspend fun refresh(): Result<Unit> = runCatching {
        val remote = loadRemoteHome()
        if (remote.first.isEmpty() && remote.second.isEmpty() &&
            (_slides.value.isNotEmpty() || _sections.value.isNotEmpty())
        ) {
            return@runCatching
        }
        _slides.value = remote.first.sortedBy { it.sortOrder }
        _sections.value = remote.second.sortedBy { it.sortOrder }
    }

    private suspend fun loadRemoteHome(): Pair<List<HomeSlide>, List<HomeSection>> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext emptyList<HomeSlide>() to emptyList()
            val request = Request.Builder()
                .url("$baseUrl/api/v1/banners?_=${System.currentTimeMillis()}")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList<HomeSlide>() to emptyList()
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext emptyList<HomeSlide>() to emptyList()
                val json = JSONObject(body)
                val banners = json.optJSONArray("banners")
                val slideList = buildList {
                    if (banners == null) return@buildList
                    for (i in 0 until banners.length()) {
                        val item = banners.optJSONObject(i) ?: continue
                        val image = item.optString("image_url").trim()
                        if (image.isBlank()) continue
                        add(
                            HomeSlide(
                                id = item.optString("id", "banner-$i"),
                                imageUrl = image,
                                title = item.optString("title").takeIf { it.isNotBlank() },
                                subtitle = item.optString("description").takeIf { it.isNotBlank() },
                                actionKey = item.optString("link_url").takeIf { it.isNotBlank() },
                                sortOrder = item.optInt("sort_order", i),
                            ),
                        )
                    }
                }
                val sectionArr = json.optJSONArray("sections")
                val sectionList = buildList {
                    if (sectionArr == null) return@buildList
                    for (i in 0 until sectionArr.length()) {
                        val item = sectionArr.optJSONObject(i) ?: continue
                        val title = item.optString("title").trim()
                        if (title.isBlank()) continue
                        add(
                            HomeSection(
                                id = item.optString("id", "section-$i"),
                                title = title,
                                subtitle = item.optString("subtitle").ifBlank {
                                    item.optString("description")
                                }.takeIf { it.isNotBlank() },
                                imageUrl = item.optString("image_url").takeIf { it.isNotBlank() },
                                actionKey = item.optString("link_url").takeIf { it.isNotBlank() },
                                sortOrder = item.optInt("sort_order", i),
                                audience = item.optString("audience", "all").ifBlank { "all" },
                            ),
                        )
                    }
                }
                slideList to sectionList
            }
        }

    companion object {
        fun filterSectionsForMode(sections: List<HomeSection>, mode: String): List<HomeSection> {
            val normalized = mode.lowercase()
            return sections.filter { section ->
                when (section.audience.lowercase()) {
                    "free" -> normalized == "free" || normalized == "subscription"
                    "subscription" -> normalized == "subscription"
                    else -> true
                }
            }
        }
    }
}
