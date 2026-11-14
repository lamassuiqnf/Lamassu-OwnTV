package tv.own.owntv.lamassu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.profile.ProfileManager
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Keeps the managed OwnTV source catalog aligned with the latest CPanel entitlement overlays.
 *
 * CPanel may change provider host/credentials or replace the exported channel set. Xtream sync
 * often cannot refresh (expired line / HTTP 451), so this seeder:
 * 1. Reuses a single managed Xtream source (updates URL/user/pass when the provider changes)
 * 2. Upserts overlay streams/categories
 * 3. Prunes Room rows that are no longer in the CPanel export
 */
class LamassuOverlayCatalogSeeder(
    context: Context,
    private val settings: SettingsRepository,
    private val profiles: ProfileManager,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
) {
    private val appContext = context.applicationContext
    private val brandName: String get() = appContext.getString(R.string.app_name)

    /**
     * @return number of stream rows upserted (0 = nothing useful in the snapshot).
     */
    suspend fun seed(snapshot: EntitlementSnapshot): Int {
        val provider = snapshot.provider ?: return 0
        if (provider.host.isBlank() || provider.username.isBlank()) return 0

        val free = snapshot.mode.equals("free", ignoreCase = true)
        val overlays = snapshot.overlays.filter { visibleForMode(it, free) }
        val source = ensureManagedSource(provider) ?: return 0

        if (overlays.isEmpty()) {
            // Do not wipe a good local catalog on an empty/partial payload (network blip,
            // temporary publish gap). Explicit empty publish still leaves categories gated
            // by allowlists via LamassuCatalogApplicator.
            Log.w(TAG, "Skipping seed — empty overlay list for source ${source.id}")
            return 0
        }

        val catIds = ensureCategories(source.id, overlays)
        var upserted = 0
        upserted += seedLive(source.id, overlays, catIds, source)
        upserted += seedMovies(source.id, overlays, catIds, source)
        upserted += seedSeries(source.id, overlays, catIds)
        pruneToExport(source.id, overlays)
        // OwnTV Core: syncLive/Movies/Series = enabledScope (browse visibility), NOT just
        // "fetch from Xtream". Setting them false after seed made Live show 0 channels even
        // with 618 rows in Room. Keep flags true for exported types; block full Xtream via
        // allowFullCatalogSync=false + pruneToExport instead.
        val exportLive = overlays.any { it.contentType.equals("live", ignoreCase = true) }
        val exportMovies = overlays.any { it.contentType.equals("movie", ignoreCase = true) }
        val exportSeries = overlays.any { it.contentType.equals("series", ignoreCase = true) }
        runCatching {
            sourceDao.update(
                source.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    syncLive = exportLive,
                    syncMovies = exportMovies,
                    syncSeries = exportSeries,
                ),
            )
        }
        Log.i(
            TAG,
            "Seeded/reconciled $upserted overlay streams into source ${source.id} " +
                "(scope live=$exportLive movies=$exportMovies series=$exportSeries)",
        )
        return upserted
    }

    private suspend fun ensureProfileId(): Long {
        val active = settings.activeProfileId.first()
        if (active >= 0L) return active
        val existing = runCatching { profileDao.observeAll().first() }.getOrNull().orEmpty()
        if (existing.isNotEmpty()) {
            settings.setActiveProfile(existing.first().id)
            return existing.first().id
        }
        val id = profiles.create(
            name = brandName,
            avatarId = -1,
            isKids = false,
            pin = null,
            fallbackName = brandName,
        )
        settings.setActiveProfile(id)
        return id
    }

    private suspend fun ensureManagedSource(provider: ManagedProviderCredentials): SourceEntity? {
        val profileId = ensureProfileId()
        if (profileId < 0L) return null
        val all = runCatching { sourceDao.getAllOnce() }.getOrNull().orEmpty()
        val hostToken = hostToken(provider.host)
        val freshUrl = provider.serverUrl()
        // Prefer exact host+user match, else the branded Lamassu Xtream row (provider switch).
        var existing = all.firstOrNull { s ->
            s.type == SourceType.XTREAM &&
                s.username.equals(provider.username, ignoreCase = true) &&
                hostToken.isNotBlank() &&
                s.url.contains(hostToken, ignoreCase = true)
        } ?: all.firstOrNull { s ->
            s.type == SourceType.XTREAM && s.name.equals(brandName, ignoreCase = true)
        } ?: all.firstOrNull { s -> s.type == SourceType.XTREAM }

        if (existing != null) {
            val providerChanged =
                !existing.username.equals(provider.username, ignoreCase = true) ||
                    (hostToken.isNotBlank() && !existing.url.contains(hostToken, ignoreCase = true)) ||
                    (provider.password.isNotBlank() && existing.password != provider.password)

            if (providerChanged) {
                Log.i(
                    TAG,
                    "Provider changed — remapping source ${existing.id} " +
                        "(${existing.username}@${existing.url} → ${provider.username}@$freshUrl)",
                )
                // Drop previous catalog so old provider content cannot linger under new credentials.
                clearCatalog(existing.id)
                existing = existing.copy(
                    name = brandName,
                    url = freshUrl.ifBlank { existing.url },
                    username = provider.username,
                    password = provider.password.ifBlank { existing.password },
                )
                runCatching { sourceDao.update(existing) }
            } else if (existing.name != brandName) {
                existing = existing.copy(name = brandName)
                runCatching { sourceDao.update(existing) }
            }

            runCatching {
                sourceDao.link(ProfileSourceCrossRef(profileId = profileId, sourceId = existing.id))
            }

            // Extra Lamassu-named duplicates → clear so only one managed source shows.
            for (other in all) {
                if (other.id == existing.id || other.type != SourceType.XTREAM) continue
                if (other.name.equals(brandName, ignoreCase = true) ||
                    other.username.equals(provider.username, ignoreCase = true)
                ) {
                    clearCatalog(other.id)
                }
            }
            return existing
        }

        // Create managed source even without password — overlay stream URLs are absolute.
        val id = sourceDao.insert(
            SourceEntity(
                name = brandName,
                type = SourceType.XTREAM,
                url = freshUrl,
                username = provider.username,
                password = provider.password,
                syncLive = true,
                syncMovies = true,
                syncSeries = true,
            ),
        )
        sourceDao.link(ProfileSourceCrossRef(profileId = profileId, sourceId = id))
        return sourceDao.getById(id)
    }

    private suspend fun clearCatalog(sourceId: Long) {
        runCatching { channelDao.clearSource(sourceId) }
        runCatching { movieDao.clearSource(sourceId) }
        runCatching { seriesDao.clearSource(sourceId) }
        for (type in listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)) {
            runCatching { categoryDao.clear(sourceId, type) }
        }
    }

    private suspend fun pruneToExport(sourceId: Long, overlays: List<ContentOverlayDto>) {
        val keepLive = overlays.filter { it.contentType.equals("live", true) }
            .map { LamassuExportIds.normalize(it.externalId) }.filter { it.isNotEmpty() }.toSet()
        val keepMovie = overlays.filter { it.contentType.equals("movie", true) }
            .map { LamassuExportIds.normalize(it.externalId) }.filter { it.isNotEmpty() }.toSet()
        val keepSeries = overlays.filter { it.contentType.equals("series", true) }
            .map { LamassuExportIds.normalize(it.externalId) }.filter { it.isNotEmpty() }.toSet()

        val keepCats = linkedMapOf<MediaType, MutableSet<String>>()
        for (o in overlays) {
            when (o.contentType.trim().lowercase()) {
                "category" -> {
                    val parsed = parseCategoryExternal(o.externalId) ?: continue
                    keepCats.getOrPut(parsed.type) { linkedSetOf() }.add(parsed.remote)
                }
                "live", "movie", "series" -> {
                    val type = when (o.contentType.trim().lowercase()) {
                        "movie" -> MediaType.MOVIE
                        "series" -> MediaType.SERIES
                        else -> MediaType.LIVE
                    }
                    normalizeCategoryRemote(o.categoryExternalId)?.let {
                        keepCats.getOrPut(type) { linkedSetOf() }.add(it)
                    }
                }
            }
        }

        // Always curated from overlays: remove anything not in the export.
        val liveGone = channelDao.remoteIdsForSource(sourceId).filter { it !in keepLive }
        if (liveGone.isNotEmpty()) channelDao.deleteByRemoteIds(sourceId, liveGone)

        val movieGone = movieDao.remoteIdsForSource(sourceId).filter { it !in keepMovie }
        if (movieGone.isNotEmpty()) movieDao.deleteByRemoteIds(sourceId, movieGone)

        val seriesGone = seriesDao.remoteIdsForSource(sourceId).filter { it !in keepSeries }
        if (seriesGone.isNotEmpty()) seriesDao.deleteByRemoteIds(sourceId, seriesGone)

        for (type in listOf(MediaType.LIVE, MediaType.MOVIE, MediaType.SERIES)) {
            val keep = keepCats[type].orEmpty()
            val existing = categoryDao.remoteIdsForSource(sourceId, type)
            val gone = existing.filter { it !in keep }
            if (gone.isNotEmpty()) categoryDao.deleteByRemoteIds(sourceId, type, gone)
        }
    }

    private suspend fun ensureCategories(
        sourceId: Long,
        overlays: List<ContentOverlayDto>,
    ): Map<String, Long> {
        val needed = linkedMapOf<String, Pair<MediaType, String>>()

        for (o in overlays) {
            when (o.contentType.trim().lowercase()) {
                "category" -> {
                    val parsed = parseCategoryExternal(o.externalId) ?: continue
                    val title = o.customTitle.trim().ifBlank { parsed.remote }
                    needed[key(parsed.type, parsed.remote)] = parsed.type to title
                }
                "live", "movie", "series" -> {
                    val type = when (o.contentType.trim().lowercase()) {
                        "movie" -> MediaType.MOVIE
                        "series" -> MediaType.SERIES
                        else -> MediaType.LIVE
                    }
                    val remote = normalizeCategoryRemote(o.categoryExternalId) ?: continue
                    val k = key(type, remote)
                    if (k !in needed) needed[k] = type to remote
                }
            }
        }

        val out = linkedMapOf<String, Long>()
        for ((composite, pair) in needed) {
            val (type, title) = pair
            val remote = composite.substringAfter(':')
            val existing = categoryDao.findByRemoteIds(sourceId, type, listOf(remote)).firstOrNull()
            if (existing != null) {
                if (existing.name != title && title != remote) {
                    runCatching { categoryDao.updateAll(listOf(existing.copy(name = title))) }
                }
                out[composite] = existing.id
                continue
            }
            val ids = categoryDao.upsertAll(
                listOf(
                    CategoryEntity(
                        sourceId = sourceId,
                        mediaType = type,
                        name = title,
                        remoteId = remote,
                        sortOrder = out.size,
                    ),
                ),
            )
            val id = ids.firstOrNull()?.takeIf { it > 0 }
                ?: categoryDao.findByRemoteIds(sourceId, type, listOf(remote)).firstOrNull()?.id
            if (id != null) out[composite] = id
        }
        return out
    }

    private suspend fun seedLive(
        sourceId: Long,
        overlays: List<ContentOverlayDto>,
        catIds: Map<String, Long>,
        source: SourceEntity,
    ): Int {
        val rows = overlays.filter { it.contentType.equals("live", ignoreCase = true) }
        if (rows.isEmpty()) return 0
        val batch = ArrayList<ChannelEntity>(rows.size)
        for ((index, o) in rows.withIndex()) {
            val remote = LamassuExportIds.normalize(o.externalId)
            if (remote.isEmpty()) continue
            val prev = channelDao.findByRemote(sourceId, remote)
            // AnotherIPTV OverlayCatalogMerge: include overlay rows even without stream_url;
            // prefer snapshot URL → previous URL → Xtream-built live URL.
            val url = o.streamUrl.trim()
                .ifBlank { prev?.streamUrl.orEmpty() }
                .ifBlank { buildXtreamLiveUrl(source, remote) }
            if (url.isEmpty()) continue
            val catRemote = normalizeCategoryRemote(o.categoryExternalId)
            val categoryId = catRemote?.let { catIds[key(MediaType.LIVE, it)] }
            batch.add(
                ChannelEntity(
                    id = prev?.id ?: 0L,
                    sourceId = sourceId,
                    categoryId = categoryId ?: prev?.categoryId,
                    name = o.customTitle.trim().ifBlank { prev?.name ?: remote },
                    logoUrl = o.customLogoUrl.trim().ifBlank { null } ?: prev?.logoUrl,
                    streamUrl = url,
                    epgChannelId = prev?.epgChannelId ?: remote,
                    remoteId = remote,
                    sortOrder = o.sortOrder ?: index,
                    catchup = prev?.catchup ?: false,
                    catchupDays = prev?.catchupDays ?: 0,
                    contentHash = prev?.contentHash ?: 0,
                ),
            )
        }
        if (batch.isEmpty()) return 0
        batch.chunked(200).forEach { channelDao.upsertAll(it) }
        return batch.size
    }

    private suspend fun seedMovies(
        sourceId: Long,
        overlays: List<ContentOverlayDto>,
        catIds: Map<String, Long>,
        source: SourceEntity,
    ): Int {
        val rows = overlays.filter { it.contentType.equals("movie", ignoreCase = true) }
        if (rows.isEmpty()) return 0
        val batch = ArrayList<MovieEntity>(rows.size)
        for ((index, o) in rows.withIndex()) {
            val remote = LamassuExportIds.normalize(o.externalId)
            if (remote.isEmpty()) continue
            val prev = movieDao.findByRemote(sourceId, remote)
            val ext = o.containerExtension.trim().ifBlank { null } ?: prev?.containerExt
            val url = o.streamUrl.trim()
                .ifBlank { prev?.streamUrl.orEmpty() }
                .ifBlank { buildXtreamMovieUrl(source, remote, ext) }
            if (url.isEmpty()) continue
            val catRemote = normalizeCategoryRemote(o.categoryExternalId)
            val categoryId = catRemote?.let { catIds[key(MediaType.MOVIE, it)] }
            batch.add(
                MovieEntity(
                    id = prev?.id ?: 0L,
                    sourceId = sourceId,
                    categoryId = categoryId ?: prev?.categoryId,
                    name = o.customTitle.trim().ifBlank { prev?.name ?: remote },
                    posterUrl = o.customLogoUrl.trim().ifBlank { null } ?: prev?.posterUrl,
                    streamUrl = url,
                    containerExt = ext,
                    remoteId = remote,
                    sortOrder = o.sortOrder ?: index,
                ),
            )
        }
        if (batch.isEmpty()) return 0
        batch.chunked(200).forEach { movieDao.upsertAll(it) }
        return batch.size
    }

    private suspend fun seedSeries(
        sourceId: Long,
        overlays: List<ContentOverlayDto>,
        catIds: Map<String, Long>,
    ): Int {
        val rows = overlays.filter { it.contentType.equals("series", ignoreCase = true) }
        if (rows.isEmpty()) return 0
        var count = 0
        for ((index, o) in rows.withIndex()) {
            val remote = LamassuExportIds.normalize(o.externalId)
            if (remote.isEmpty()) continue
            val catRemote = normalizeCategoryRemote(o.categoryExternalId)
            val categoryId = catRemote?.let { catIds[key(MediaType.SERIES, it)] }
            val prev = seriesDao.findSeriesByRemote(sourceId, remote)
            val entity = SeriesEntity(
                id = prev?.id ?: 0L,
                sourceId = sourceId,
                categoryId = categoryId ?: prev?.categoryId,
                name = o.customTitle.trim().ifBlank { prev?.name ?: remote },
                posterUrl = o.customLogoUrl.trim().ifBlank { null } ?: prev?.posterUrl,
                remoteId = remote,
                sortOrder = o.sortOrder ?: index,
            )
            val ids = seriesDao.upsertSeriesReturnIds(listOf(entity))
            val seriesId = ids.firstOrNull()?.takeIf { it > 0 }
                ?: seriesDao.findSeriesByRemote(sourceId, remote)?.id
                ?: continue
            count++
            // AnotherIPTV: overlay stream_url ⇒ direct-play series (seasonsLoaded). OwnTV: S01E01.
            val direct = o.streamUrl.trim()
            if (direct.isNotEmpty()) {
                val existing = seriesDao.findEpisodeByRemote(seriesId, "overlay:$remote")
                seriesDao.upsertEpisodes(
                    listOf(
                        EpisodeEntity(
                            id = existing?.id ?: 0L,
                            seriesId = seriesId,
                            seasonId = null,
                            seasonNumber = 1,
                            episodeNumber = 1,
                            name = entity.name,
                            streamUrl = direct,
                            containerExt = o.containerExtension.trim().ifBlank { null },
                            remoteId = "overlay:$remote",
                        ),
                    ),
                )
                seriesDao.markEpisodesSynced(seriesId, System.currentTimeMillis())
            }
        }
        return count
    }

    /** Mirror OwnTV XtreamClient.liveUrl when CPanel snapshot has no absolute URL. */
    private fun buildXtreamLiveUrl(source: SourceEntity, streamId: String): String {
        val user = source.username.orEmpty()
        val pass = source.password.orEmpty()
        if (user.isBlank() || pass.isBlank() || source.url.isBlank()) return ""
        val base = source.url.trim().trimEnd('/')
        return "$base/live/$user/$pass/$streamId.ts"
    }

    private fun buildXtreamMovieUrl(source: SourceEntity, streamId: String, ext: String?): String {
        val user = source.username.orEmpty()
        val pass = source.password.orEmpty()
        if (user.isBlank() || pass.isBlank() || source.url.isBlank()) return ""
        val base = source.url.trim().trimEnd('/')
        val e = ext?.trim().orEmpty().ifBlank { "mp4" }
        return "$base/movie/$user/$pass/$streamId.$e"
    }

    private data class ParsedCategory(val type: MediaType, val remote: String)

    private fun parseCategoryExternal(externalId: String): ParsedCategory? {
        val ext = externalId.trim()
        if (ext.isEmpty()) return null
        val lower = ext.lowercase()
        return when {
            lower.startsWith("live:") -> ParsedCategory(MediaType.LIVE, ext.substringAfter(':').trim())
            lower.startsWith("movie:") -> ParsedCategory(MediaType.MOVIE, ext.substringAfter(':').trim())
            lower.startsWith("series:") -> ParsedCategory(MediaType.SERIES, ext.substringAfter(':').trim())
            else -> ParsedCategory(MediaType.LIVE, LamassuExportIds.normalize(ext))
        }.takeIf { it.remote.isNotEmpty() }
    }

    private fun normalizeCategoryRemote(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        for (prefix in listOf("live:", "movie:", "series:")) {
            if (v.startsWith(prefix, ignoreCase = true)) {
                return v.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
            }
        }
        return LamassuExportIds.normalize(v).takeIf { it.isNotEmpty() }
    }

    private fun key(type: MediaType, remote: String): String = "${type.name.lowercase()}:$remote"

    private fun hostToken(host: String): String =
        host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()

    private fun visibleForMode(o: ContentOverlayDto, free: Boolean): Boolean {
        val vis = o.visibility.lowercase().ifBlank { "both" }
        return when {
            vis == "hidden" || vis == "none" -> false
            free && vis == "subscription" -> false
            else -> true
        }
    }

    companion object {
        private const val TAG = "LamassuOverlaySeed"
    }
}
