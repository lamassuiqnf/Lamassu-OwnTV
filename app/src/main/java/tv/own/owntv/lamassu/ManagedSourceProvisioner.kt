package tv.own.owntv.lamassu

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.SyncScopeChoice

/**
 * Provisions the CPanel managed Xtream account into OwnTV via [SourceImporter].
 */
class ManagedSourceProvisioner(
    context: Context,
    private val importer: SourceImporter,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val settings: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private fun str(id: Int): String = appContext.getString(id)

    suspend fun ensureProvisioned(
        provider: ManagedProviderCredentials?,
        onProgress: (percent: Int, message: String) -> Unit = { _, _ -> },
    ): Result<Unit> {
        if (provider == null || provider.host.isBlank() || provider.username.isBlank()) {
            onProgress(100, "")
            return Result.success(Unit)
        }

        val existing = findManaged(provider)
        if (existing != null) {
            val count = runCatching { channelDao.countForSourceOnce(existing.id) }.getOrDefault(0)
            if (count > 0) {
                onProgress(100, str(R.string.lamassu_status_ready))
                return Result.success(Unit)
            }
            if (provider.password.isBlank()) {
                onProgress(100, "")
                return Result.success(Unit)
            }
        } else if (provider.password.isBlank()) {
            onProgress(100, "")
            return Result.success(Unit)
        }

        onProgress(20, str(R.string.lamassu_status_connecting))
        val server = provider.serverUrl()
        return runCatching {
            ensureProfileReady()
            importer.xtream(
                name = provider.name.ifBlank { appContext.getString(R.string.app_name) },
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
                is SourceImporter.ImportState.Failed -> {
                    error(done.failure.toString())
                }
                else -> error(str(R.string.lamassu_error_sync_timeout))
            }
        }
    }

    suspend fun refreshManagedCatalog(
        provider: ManagedProviderCredentials?,
        onProgress: (percent: Int, message: String) -> Unit = { _, _ -> },
    ): Result<Unit> = ensureProvisioned(provider, onProgress)

    suspend fun hasBrowsableCatalog(): Boolean {
        val sources = runCatching { sourceDao.getAllOnce() }.getOrNull().orEmpty()
        for (s in sources) {
            if (s.type != SourceType.XTREAM) continue
            val count = runCatching { channelDao.countForSourceOnce(s.id) }.getOrDefault(0)
            if (count > 0) return true
        }
        return false
    }

    private suspend fun findManaged(provider: ManagedProviderCredentials) =
        runCatching { sourceDao.getAllOnce() }.getOrNull().orEmpty()
            .firstOrNull { s ->
                s.type == SourceType.XTREAM &&
                    s.username.equals(provider.username, ignoreCase = true) &&
                    (
                        s.url.contains(
                            provider.host.trim()
                                .removePrefix("http://")
                                .removePrefix("https://")
                                .substringBefore('/'),
                            ignoreCase = true,
                        ) || provider.host.isBlank()
                        )
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
