package tv.own.owntv.lamassu

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncScopeChoice
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/**
 * Provisions the managed catalog account into OwnTV via [SourceImporter], and refreshes it on reopen.
 *
 * Xtream sync is best-effort. CPanel overlay seeding supplies curated Live/VOD when the line
 * is expired or blocked (HTTP 451). Failed imports must not delete the managed source row.
 */
class ManagedSourceProvisioner(
    context: Context,
    private val importer: SourceImporter,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val settings: SettingsRepository,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val importFinalizer: ImportFinalizer,
) {
    private val appContext = context.applicationContext
    private fun str(id: Int): String = appContext.getString(id)

    /**
     * @param allowFullCatalogSync When false (default after a CPanel overlay seed), never pull the
     * full Xtream library — that would reintroduce non-exported channels/movies/series.
     */
    suspend fun ensureProvisioned(
        provider: ManagedProviderCredentials?,
        @Suppress("UNUSED_PARAMETER") forceSync: Boolean = false,
        allowFullCatalogSync: Boolean = false,
        onProgress: (percent: Int, message: String) -> Unit = { _, _ -> },
    ): Result<Unit> {
        if (provider == null || provider.host.isBlank() || provider.username.isBlank()) {
            onProgress(100, "")
            return Result.success(Unit)
        }

        val brandName = appContext.getString(R.string.app_name)
        val existing = findManaged(provider)
        if (existing != null) {
            var source = existing
            val freshServer = provider.serverUrl()
            val credsChanged = provider.password.isNotBlank() && (
                existing.password != provider.password ||
                    !existing.username.equals(provider.username, true) ||
                    (freshServer.isNotBlank() && existing.url != freshServer)
                )
            if (source.name != brandName) {
                source = source.copy(name = brandName)
                runCatching { sourceDao.update(source) }
            }
            if (credsChanged) {
                source = source.copy(
                    username = provider.username,
                    password = provider.password,
                    url = freshServer.ifBlank { source.url },
                )
                runCatching { sourceDao.update(source) }
            }
            if (hasBrowsableForSource(source.id)) {
                // Overlay-seeded / already-synced catalog: refresh credentials only.
                // AnotherIPTV also early-returns when channels already exist.
                onProgress(100, str(R.string.lamassu_status_ready))
                return Result.success(Unit)
            }
            if (allowFullCatalogSync && provider.password.isNotBlank()) {
                onProgress(40, str(R.string.lamassu_status_syncing))
                enqueueCatalogSync(source.id, reason = "lamassu_empty_refresh")
            }
            onProgress(100, str(R.string.lamassu_status_ready))
            return Result.success(Unit)
        } else if (provider.password.isBlank() || !allowFullCatalogSync) {
            onProgress(100, "")
            return Result.success(Unit)
        }

        onProgress(20, str(R.string.lamassu_status_connecting))
        val server = provider.serverUrl()
        return runCatching {
            ensureProfileReady()
            // Keep the source row if Xtream fails so a later overlay seed can fill Room.
            // Prefer seeding BEFORE this path whenever overlays already carry stream URLs.
            importer.backgroundHandoff = true
            try {
                importer.xtream(
                    name = appContext.getString(R.string.app_name),
                    server = server,
                    username = provider.username,
                    password = provider.password,
                    userAgent = "",
                    epgUrl = "",
                    autoRefresh = PlaylistRefresh.OFF,
                    live = SyncScopeChoice.Now,
                    movies = SyncScopeChoice.Now,
                    series = SyncScopeChoice.Now,
                    preferHls = false,
                    makeDefault = true,
                    httpReferer = "",
                )
                onProgress(60, str(R.string.lamassu_status_syncing))
                val done = withTimeoutOrNull(180_000L) {
                    importer.state.first {
                        it is SourceImporter.ImportState.Success || it is SourceImporter.ImportState.Failed
                    }
                }
                when (done) {
                    is SourceImporter.ImportState.Success -> {
                        runCatching { importer.finish() }
                        onProgress(100, str(R.string.lamassu_status_ready))
                    }
                    is SourceImporter.ImportState.Failed, null -> {
                        runCatching { importer.finish() }
                        onProgress(100, str(R.string.lamassu_status_ready))
                    }
                    else -> {
                        runCatching { importer.finish() }
                        onProgress(100, str(R.string.lamassu_status_ready))
                    }
                }
            } finally {
                importer.backgroundHandoff = false
            }
        }
    }

    private suspend fun enqueueCatalogSync(sourceId: Long, reason: String) {
        val source = sourceDao.getById(sourceId) ?: return
        val counts = runCatching { importFinalizer.contentCounts(sourceId) }.getOrNull()
        catalogSyncScheduler.enqueueSync(
            sourceId = sourceId,
            reason = reason,
            contentTypes = SyncContentTypes.enabledOf(source),
            baseItemCount = (counts?.channels ?: 0) + (counts?.movies ?: 0) + (counts?.series ?: 0),
            policy = androidx.work.ExistingWorkPolicy.KEEP,
        )
    }

    suspend fun refreshManagedCatalog(
        provider: ManagedProviderCredentials?,
        onProgress: (percent: Int, message: String) -> Unit = { _, _ -> },
    ): Result<Unit> = ensureProvisioned(
        provider,
        forceSync = true,
        // Do not dump non-exported Xtream over a CPanel overlay seed; coordinator re-seeds instead.
        allowFullCatalogSync = false,
        onProgress = onProgress,
    )

    suspend fun hasBrowsableCatalog(): Boolean {
        val sources = runCatching { sourceDao.getAllOnce() }.getOrNull().orEmpty()
        for (s in sources) {
            if (s.type != SourceType.XTREAM) continue
            if (hasBrowsableForSource(s.id)) return true
        }
        return false
    }

    private suspend fun hasBrowsableForSource(sourceId: Long): Boolean {
        val channels = runCatching { channelDao.countForSourceOnce(sourceId) }.getOrDefault(0)
        if (channels > 0) return true
        val movies = runCatching { movieDao.countForSourceOnce(sourceId) }.getOrDefault(0)
        if (movies > 0) return true
        val series = runCatching { seriesDao.countForSourceOnce(sourceId) }.getOrDefault(0)
        return series > 0
    }

    private suspend fun findManaged(provider: ManagedProviderCredentials): tv.own.owntv.core.database.entity.SourceEntity? {
        val brand = appContext.getString(R.string.app_name)
        val host = provider.host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
        val all = runCatching { sourceDao.getAllOnce() }.getOrNull().orEmpty()
        return all.firstOrNull { s ->
            s.type == SourceType.XTREAM &&
                s.username.equals(provider.username, ignoreCase = true) &&
                (host.isBlank() || s.url.contains(host, ignoreCase = true))
        } ?: all.firstOrNull { s ->
            s.type == SourceType.XTREAM && s.name.equals(brand, ignoreCase = true)
        }
    }

    private suspend fun ensureProfileReady() {
        val active = settings.activeProfileId.first()
        if (active >= 0L) {
            importer.useProfile(active)
            return
        }
        importer.createProfile(appContext.getString(R.string.app_name), 0, false, null)
    }
}
