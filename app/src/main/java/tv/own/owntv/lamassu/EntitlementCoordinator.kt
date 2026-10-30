package tv.own.owntv.lamassu

import android.content.Context
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
                _gate.value = EntitlementGateState.Allowed
                scheduleBackgroundProvision(cached, refreshCredentials = true)
                val age = if (cached.fetchedAtMs > 0) {
                    System.currentTimeMillis() - cached.fetchedAtMs
                } else {
                    Long.MAX_VALUE
                }
                val needsNetwork = forceRefresh || age >= 15 * 60_000L
                if (needsNetwork) {
                    bgScope.launch { revalidateInBackground(cached) }
                } else {
                    bgScope.launch { refreshConfigQuietly() }
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
                    _importProgressPercent.value = 12
                    val provision = withTimeoutOrNull(180_000L) {
                        provisioner.ensureProvisioned(provider) { percent, message ->
                            _importProgressPercent.value = percent.coerceIn(0, 100)
                            if (message.isNotBlank()) _statusAr.value = message
                        }
                    }
                    if (provision == null || provision.isFailure || !provisioner.hasBrowsableCatalog()) {
                        scheduleBackgroundProvision(snap, refreshCredentials = false)
                    }
                    if (provision?.isSuccess == true && provisioner.hasBrowsableCatalog()) {
                        val stripped = snap.copy(provider = provider.copy(password = ""))
                        _snapshot.value = stripped
                        cache.set(stripped)
                        _importProgressPercent.value = 100
                    } else {
                        cache.set(snap)
                    }
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

    private suspend fun revalidateInBackground(cached: EntitlementSnapshot) {
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
                val provisioned = runCatching {
                    provisioner.ensureProvisioned(snap.provider)
                }.getOrNull()
                if (provisioned?.isSuccess == true && provisioner.hasBrowsableCatalog()) {
                    val stripped = snap.copy(provider = snap.provider?.copy(password = ""))
                    _snapshot.value = stripped
                    cache.set(stripped)
                } else {
                    cache.set(snap)
                    scheduleBackgroundProvision(snap, refreshCredentials = false)
                }
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

    private fun scheduleBackgroundProvision(
        snap: EntitlementSnapshot,
        refreshCredentials: Boolean = true,
    ) {
        val seed = snap.provider ?: return
        bgScope.launch {
            var provider = seed
            if (refreshCredentials || provider.password.isBlank()) {
                val installId = runCatching { installIdStore.getOrCreate() }.getOrNull()
                if (installId != null) {
                    val live = withTimeoutOrNull(28_000L) { api.validate(installId, snap) }
                    if (live is ApiResult.Success) {
                        val fresh = live.data
                        _snapshot.value = fresh
                        publishAccess(fresh)
                        provider = fresh.provider ?: provider
                        cache.set(fresh)
                    }
                }
            }
            val result = runCatching { provisioner.ensureProvisioned(provider) }.getOrNull()
            if (
                result?.isSuccess == true &&
                provider.password.isNotBlank() &&
                provisioner.hasBrowsableCatalog()
            ) {
                val stripped = (_snapshot.value ?: snap).copy(
                    provider = provider.copy(password = ""),
                )
                _snapshot.value = stripped
                cache.set(stripped)
            }
        }
    }

    private fun publishAccess(snap: EntitlementSnapshot) {
        overlayStore.replaceAll(snap.overlays, snap.mode)
        accessStore.update(
            EntitlementAccess(
                mode = snap.mode,
                allowLive = snap.permissions.allowLive,
                allowMovies = snap.permissions.allowMovies,
                allowSeries = snap.permissions.allowSeries,
                managedOnlyProviders = snap.settings.managedOnlyProviders,
            ),
        )
    }
}
