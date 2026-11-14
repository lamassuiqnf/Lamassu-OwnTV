package tv.own.owntv.lamassu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.R

class EntitlementCoordinator(
    context: Context,
    private val api: EntitlementApiClient,
    private val cache: EntitlementCacheStore,
    private val installIdStore: InstallIdStore,
    private val provisioner: ManagedSourceProvisioner,
    private val accessStore: EntitlementAccessStore,
    private val overlayStore: ContentOverlayStore,
    private val catalogApplicator: LamassuCatalogApplicator,
    private val overlaySeeder: LamassuOverlayCatalogSeeder,
) {
    private val appContext = context.applicationContext
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun str(id: Int): String = appContext.getString(id)

    private val _gate = MutableStateFlow<EntitlementGateState>(EntitlementGateState.Checking)
    val gateState: StateFlow<EntitlementGateState> = _gate.asStateFlow()

    private val _snapshot = MutableStateFlow<EntitlementSnapshot?>(null)
    val snapshot: StateFlow<EntitlementSnapshot?> = _snapshot.asStateFlow()

    private val _remoteConfig = MutableStateFlow(RemoteAppConfig())
    val remoteConfig: StateFlow<RemoteAppConfig> = _remoteConfig.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _errorAr = MutableStateFlow<String?>(null)
    val errorAr: StateFlow<String?> = _errorAr.asStateFlow()

    private val _statusAr = MutableStateFlow<String?>(null)
    val statusAr: StateFlow<String?> = _statusAr.asStateFlow()

    private val _importProgressPercent = MutableStateFlow<Int?>(null)
    val importProgressPercent: StateFlow<Int?> = _importProgressPercent.asStateFlow()

    fun managedOnlyProviders(): Boolean =
        _snapshot.value?.settings?.managedOnlyProviders
            ?: _remoteConfig.value.managedOnlyProviders

    suspend fun evaluate(forceRefresh: Boolean = false) {
        _errorAr.value = null
        try {
            val cached = cache.get()
            _snapshot.value = cached

            if (cached != null && cached.mode != "none" && cached.status == "active") {
                publishAccess(cached)
                // Apply last-known CPanel catalog immediately so Live is never blank while
                // revalidate is in flight (and works offline within grace).
                applyPanelCatalog(cached, label = "cached")
                _gate.value = EntitlementGateState.Allowed
                bgScope.launch {
                    refreshConfigQuietly()
                    revalidateInBackground(cached, forceRefresh = forceRefresh)
                }
                return
            }

            _gate.value = EntitlementGateState.Checking
            _statusAr.value = str(R.string.lamassu_status_checking)
            refreshConfigQuietly()

            if (_remoteConfig.value.maintenanceMode) {
                _gate.value = EntitlementGateState.Maintenance(
                    _remoteConfig.value.announcement.ifBlank { str(R.string.lamassu_maintenance_default) },
                )
                return
            }

            val installId = installIdStore.getOrCreate()
            val fingerprint = installIdStore.deviceFingerprint()
            when (val restored = withTimeoutOrNull(20_000L) {
                api.restoreByDevice(installId, fingerprint)
            }) {
                is ApiResult.Success -> {
                    val snap = restored.data
                    cache.set(snap)
                    _snapshot.value = snap
                    publishAccess(snap)
                    applyPanelCatalog(snap, label = "restore")
                    scheduleBackgroundProvision(snap, refreshCredentials = false)
                    _gate.value = EntitlementGateState.Allowed
                    return
                }
                else -> Unit
            }

            _gate.value = EntitlementGateState.NeedsActivation
        } catch (_: Exception) {
            val cached = runCatching { cache.get() }.getOrNull()
            if (cached != null && cached.status == "active") {
                publishAccess(cached)
                applyPanelCatalog(cached, label = "grace")
                _gate.value = EntitlementGateState.ConnectionGrace(cached)
            } else {
                _gate.value = EntitlementGateState.NeedsActivation
            }
        } finally {
            _statusAr.value = null
            if (_gate.value == EntitlementGateState.Checking) {
                _gate.value = EntitlementGateState.NeedsActivation
            }
        }
    }

    suspend fun activateFree() {
        val installId = installIdStore.getOrCreate()
        val fingerprint = installIdStore.deviceFingerprint()
        runActivation(status = str(R.string.lamassu_status_free)) {
            api.activateFree(installId, fingerprint)
        }
    }

    suspend fun activateCode(code: String) {
        val installId = installIdStore.getOrCreate()
        val fingerprint = installIdStore.deviceFingerprint()
        runActivation(status = str(R.string.lamassu_status_code)) {
            api.activateCode(installId, code, fingerprint)
        }
    }

    suspend fun clearActivation() {
        cache.clear()
        _snapshot.value = null
        accessStore.update(EntitlementAccess.none())
        _gate.value = EntitlementGateState.NeedsActivation
    }

    private suspend fun runActivation(
        status: String,
        block: suspend () -> ApiResult<EntitlementSnapshot>,
    ) {
        _busy.value = true
        _errorAr.value = null
        _statusAr.value = status
        _importProgressPercent.value = 5
        try {
            val result = withTimeoutOrNull(28_000L) { block() }
                ?: ApiResult.Error(str(R.string.lamassu_error_timeout))
            when (result) {
                is ApiResult.Success -> {
                    val snap = result.data
                    val provider = snap.provider
                    if (provider == null || provider.host.isBlank() || provider.username.isBlank()) {
                        _errorAr.value = str(R.string.lamassu_error_content)
                        _gate.value = EntitlementGateState.NeedsActivation
                        return
                    }
                    cache.set(snap)
                    _snapshot.value = snap
                    publishAccess(snap)
                    _statusAr.value = str(R.string.lamassu_status_import)
                    _importProgressPercent.value = 35
                    // Seed Room from CPanel overlays first — do not wait on Xtream.
                    // Stream URLs are already in the entitlement payload.
                    val seeded = applyPanelCatalog(snap, label = "activate")
                    _importProgressPercent.value = 70
                    val hasExportStreams = snap.overlays.any {
                        val t = it.contentType.trim().lowercase()
                        t == "live" || t == "movie" || t == "series"
                    }
                    if (seeded <= 0 && !hasExportStreams) {
                        // No CPanel export streams — legacy fallback: one Xtream pull, then gate.
                        withTimeoutOrNull(45_000L) {
                            provisioner.ensureProvisioned(
                                provider,
                                allowFullCatalogSync = true,
                            ) { percent, message ->
                                _importProgressPercent.value = (70 + percent / 4).coerceIn(70, 99)
                                if (message.isNotBlank()) _statusAr.value = message
                            }
                        }
                        applyPanelCatalog(snap, label = "activate-after-xtream")
                    } else {
                        // Overlay seed = AnotherIPTV OverlayCatalogMerge path (proven when Xtream
                        // is blocked). Series episodes resolve lazily on open via credentials.
                        scheduleBackgroundProvision(snap, refreshCredentials = false)
                    }
                    val stripped = snap.copy(provider = provider.copy(password = ""))
                    _snapshot.value = stripped
                    cache.set(stripped)
                    _importProgressPercent.value = 100
                    _gate.value = EntitlementGateState.Allowed
                }
                is ApiResult.Error -> {
                    _errorAr.value = CustomerFacingText.sanitize(
                        result.message,
                        fallback = str(R.string.lamassu_error_generic),
                    )
                    _gate.value = EntitlementGateState.NeedsActivation
                }
            }
        } catch (e: Exception) {
            _errorAr.value = CustomerFacingText.sanitize(
                e.message,
                fallback = str(R.string.lamassu_error_generic),
            )
            _gate.value = EntitlementGateState.NeedsActivation
        } finally {
            _busy.value = false
            _statusAr.value = null
            _importProgressPercent.value = null
        }
    }

    private suspend fun revalidateInBackground(
        cached: EntitlementSnapshot,
        forceRefresh: Boolean = false,
    ) {
        refreshConfigQuietly()
        if (_remoteConfig.value.maintenanceMode) {
            _gate.value = EntitlementGateState.Maintenance(
                _remoteConfig.value.announcement.ifBlank { str(R.string.lamassu_maintenance_default) },
            )
            return
        }
        val installId = installIdStore.getOrCreate()
        when (val validated = withTimeoutOrNull(25_000L) { api.validate(installId, cached) }) {
            is ApiResult.Success -> {
                val snap = validated.data
                _snapshot.value = snap
                publishAccess(snap)
                val mergedProvider = snap.provider?.let { fresh ->
                    if (fresh.password.isNotBlank()) fresh
                    else fresh.copy(password = cached.provider?.password.orEmpty())
                }
                val merged = snap.copy(provider = mergedProvider)
                // Panel catalog first — Xtream is optional enrichment.
                applyPanelCatalog(merged, label = if (forceRefresh) "force-refresh" else "revalidate")
                scheduleBackgroundProvision(merged, refreshCredentials = false, forceSync = forceRefresh)
                publishAccess(merged)
                val stripped = merged.copy(provider = merged.provider?.copy(password = ""))
                _snapshot.value = stripped
                cache.set(stripped)
                if (_gate.value !is EntitlementGateState.Maintenance) {
                    _gate.value = EntitlementGateState.Allowed
                }
            }
            is ApiResult.Error -> {
                val msg = validated.message
                if (msg.contains("انتهى") || msg.contains("expired", ignoreCase = true) ||
                    msg.contains("الغاء") || msg.contains("revoked", ignoreCase = true) ||
                    msg.contains("حظر") || msg.contains("free_mode_disabled", ignoreCase = true) ||
                    msg.contains("غير متاحة")
                ) {
                    cache.clear()
                    _snapshot.value = null
                    accessStore.update(EntitlementAccess.none())
                    val safe = CustomerFacingText.sanitize(msg, fallback = str(R.string.lamassu_error_expired))
                    _gate.value = if (msg.contains("انتهى") || msg.contains("expired", ignoreCase = true)) {
                        EntitlementGateState.Expired(safe)
                    } else {
                        EntitlementGateState.NeedsActivation
                    }
                    _errorAr.value = safe.takeIf { it.isNotBlank() }
                }
            }
            null -> Unit
        }
    }

    private suspend fun refreshConfigQuietly() {
        when (val configResult = withTimeoutOrNull(12_000L) { api.fetchConfig() }) {
            is ApiResult.Success -> _remoteConfig.value = configResult.data
            else -> Unit
        }
    }

    /**
     * Writes CPanel overlays into Room and projects allowlists/custom folders.
     * @return number of stream rows upserted by the seeder
     */
    private suspend fun applyPanelCatalog(snap: EntitlementSnapshot, label: String): Int {
        val seeded = runCatching { overlaySeeder.seed(snap) }
            .onFailure { Log.e(TAG, "overlay seed failed ($label)", it) }
            .getOrDefault(0)
        // Always project allowlists after seed — even when seeded==0 (cached Room already filled).
        runCatching { catalogApplicator.apply(snap) }
            .onFailure { Log.e(TAG, "catalog apply failed ($label)", it) }
        val liveOverlays = snap.overlays.count {
            it.contentType.equals("live", ignoreCase = true)
        }
        Log.i(
            TAG,
            "panel catalog ($label): seeded=$seeded liveOverlays=$liveOverlays " +
                "overlays=${snap.overlays.size} provider=${snap.provider?.host.orEmpty()}",
        )
        return seeded
    }

    private fun scheduleBackgroundProvision(
        snap: EntitlementSnapshot,
        refreshCredentials: Boolean = true,
        forceSync: Boolean = false,
    ) {
        val seed = snap.provider ?: return
        bgScope.launch {
            var provider = seed
            var gotFreshCredentials = false
            if (refreshCredentials || provider.password.isBlank() || forceSync) {
                val installId = runCatching { installIdStore.getOrCreate() }.getOrNull()
                if (installId != null) {
                    when (val live = withTimeoutOrNull(28_000L) { api.validate(installId, snap) }) {
                        is ApiResult.Success -> {
                            val fresh = live.data
                            _snapshot.value = fresh
                            publishAccess(fresh)
                            provider = fresh.provider ?: provider
                            gotFreshCredentials = provider.password.isNotBlank()
                            cache.set(fresh)
                            applyPanelCatalog(fresh.copy(provider = provider), label = "bg-validate")
                        }
                        is ApiResult.Error -> {
                            Log.w(
                                TAG,
                                "validate on open failed: ${CustomerFacingText.sanitize(live.message)}",
                            )
                        }
                        null -> Unit
                    }
                }
            }
            runCatching {
                provisioner.ensureProvisioned(
                    provider,
                    forceSync = forceSync && (gotFreshCredentials || provider.password.isNotBlank()),
                    // Full Xtream only when CPanel sent no export overlays (legacy). Otherwise
                    // overlay seed+prune is the catalog — same net result as AnotherIPTV's
                    // overlay merge when the Xtream line is unavailable.
                    allowFullCatalogSync = snap.overlays.none {
                        val t = it.contentType.trim().lowercase()
                        t == "live" || t == "movie" || t == "series" || t == "category"
                    },
                )
            }.onFailure { Log.w(TAG, "background Xtream provision failed", it) }
            val live = _snapshot.value ?: snap
            val withProvider = live.copy(provider = provider)
            // Re-seed after Xtream so any newly imported VOD episodes merge; prune keeps CPanel set.
            applyPanelCatalog(withProvider, label = "bg-after-xtream")
            if (provider.password.isNotBlank()) {
                val stripped = live.copy(provider = provider.copy(password = ""))
                _snapshot.value = stripped
                cache.set(stripped)
            }
        }
    }

    private fun publishAccess(snap: EntitlementSnapshot) {
        overlayStore.replaceAll(snap.overlays, snap.mode)
        // Gate allow_* from resolved export (overlay + server). Allowlists follow AnotherIPTV:
        // prefer server publish lists, fall back to overlay-derived when server omitted them.
        val gate = LamassuExportPermissions.resolve(snap)
        accessStore.update(
            EntitlementAccess(
                mode = snap.mode,
                allowLive = gate.allowLive,
                allowMovies = gate.allowMovies,
                allowSeries = gate.allowSeries,
                liveCategoryAllowlist = gate.liveCategoryAllowlist.toSet(),
                liveStreamAllowlist = gate.liveStreamAllowlist.toSet(),
                movieCategoryAllowlist = gate.movieCategoryAllowlist.toSet(),
                movieStreamAllowlist = gate.movieStreamAllowlist.toSet(),
                seriesCategoryAllowlist = gate.seriesCategoryAllowlist.toSet(),
                seriesStreamAllowlist = gate.seriesStreamAllowlist.toSet(),
                managedOnlyProviders = snap.settings.managedOnlyProviders,
                bannerIntervalSeconds = snap.settings.bannerInterval.coerceIn(1, 60),
            ),
        )
    }

    companion object {
        private const val TAG = "LamassuEntitlement"
    }
}
