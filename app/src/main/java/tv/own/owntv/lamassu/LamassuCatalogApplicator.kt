package tv.own.owntv.lamassu

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomCategory
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.CustomCategoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.CustomCategoryMemberEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Projects CPanel export (allowlists + overlays) onto OwnTV's [CustomizationStore] so Live /
 * Movies / Series only show exported **sections** (categories) and items — not the full Xtream dump.
 *
 * Manual CPanel categories (`manual_*` remote ids, or category overlays that have no matching
 * Xtream folder) become OwnTV [CustomCategory] folders; stream overlays that point at those
 * categories are membership + [SectionCustomizations.movedFromOrigin].
 */
class LamassuCatalogApplicator(
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val sourceDao: SourceDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val customCategoryDao: CustomCategoryDao,
) {
    suspend fun apply(snapshot: EntitlementSnapshot) {
        val provider = snapshot.provider ?: return
        val source = findManaged(provider) ?: return
        val profileId = settings.activeProfileId.first().takeIf { it >= 0L } ?: return
        val gate = LamassuExportPermissions.resolve(snapshot)

        // New Xtream folders that appear after a background sync stay hidden until exported.
        settings.setHideNewCategoriesDefault(profileId, true)

        applyMedia(
            profileId, source.id, MediaType.LIVE, gate.allowLive,
            gate.liveCategoryAllowlist, gate.liveStreamAllowlist, snapshot, gate.curated,
        )
        applyMedia(
            profileId, source.id, MediaType.MOVIE, gate.allowMovies,
            gate.movieCategoryAllowlist, gate.movieStreamAllowlist, snapshot, gate.curated,
        )
        applyMedia(
            profileId, source.id, MediaType.SERIES, gate.allowSeries,
            gate.seriesCategoryAllowlist, gate.seriesStreamAllowlist, snapshot, gate.curated,
        )
    }

    private suspend fun applyMedia(
        profileId: Long,
        sourceId: Long,
        type: MediaType,
        allowed: Boolean,
        categoryAllowRaw: List<String>,
        streamAllowRaw: List<String>,
        snapshot: EntitlementSnapshot,
        curated: Boolean,
    ) {
        val categories = categoryDao.observe(listOf(sourceId), type).first()
        val byRemote = categories.mapNotNull { c -> c.remoteId?.let { it to c } }.toMap()
        val byId = categories.associateBy { it.id }
        val streamAllow = LamassuExportIds.normalizeSet(streamAllowRaw)
        val categoryAllow = LamassuExportIds.normalizeSet(categoryAllowRaw)
        val hadNone = LamassuExportIds.hasNone(categoryAllowRaw)
        val overlayCats = categoryOverlays(snapshot.overlays, type, snapshot.mode)
        // Stream overlays for this type act as an implicit selective export allowlist.
        val overlayStreamRemotes = streamOverlayRemotes(snapshot.overlays, type, snapshot.mode)
        val effectiveStreamAllow = if (streamAllow.isNotEmpty()) streamAllow else overlayStreamRemotes

        if (categories.isEmpty()) {
            val cpanelCustoms = applyCpanelCustomCategories(
                profileId, sourceId, type, snapshot, byRemote,
            )
            customize.update(profileId, type) {
                val keptUserCustoms = it.customCategories.filterNot { c -> isCpanelCustomId(c.id) }
                val keptMoved = it.movedFromOrigin.filterKeys { key -> key !in cpanelCustoms.movedKeys }
                SectionCustomizations(
                    // Clear stale hides when Room is empty; customs may still show.
                    hiddenCategories = emptySet(),
                    hiddenItems = emptyMap(),
                    categoryNames = emptyMap(),
                    itemNames = itemOverlayNames(snapshot.overlays, type, snapshot.mode, sourceId),
                    categoryOrder = cpanelCustoms.customCategories.map { c -> c.id },
                    epgMatches = it.epgMatches,
                    epgShifts = it.epgShifts,
                    customCategories = keptUserCustoms + cpanelCustoms.customCategories,
                    movedFromOrigin = keptMoved + cpanelCustoms.movedFromOrigin,
                    lockedCategories = it.lockedCategories,
                )
            }
            return
        }

        val titleByKey = buildMap {
            overlayCats.forEach { (remote, title, _) ->
                if (title.isBlank()) return@forEach
                val cat = byRemote[remote]
                    ?: byRemote.entries.firstOrNull {
                        LamassuExportIds.normalize(it.key) == LamassuExportIds.normalize(remote)
                    }?.value
                    ?: return@forEach
                put(CustomizeKeys.category(cat), title)
            }
        }

        val orderedKeys = overlayCats.mapNotNull { (remote, _, _) ->
            val cat = byRemote[remote]
                ?: byRemote.entries.firstOrNull {
                    LamassuExportIds.normalize(it.key) == LamassuExportIds.normalize(remote)
                }?.value
            cat?.let { CustomizeKeys.category(it) }
        }

        val exportedRemotes = overlayCats.map { it.first }.toSet()
        val cpanelCustomRemotes = cpanelCustomCategoryRemotes(overlayCats, byRemote, snapshot, type)

        val streamOverlayCats = streamOverlayCategoryRemotes(snapshot.overlays, type, snapshot.mode)

        val projection = when {
            // `__none__` closes a type only when there is also no stream export for it.
            // (Selective stream exports often ship category=__none__ + non-empty stream allowlist.)
            !allowed || (hadNone && effectiveStreamAllow.isEmpty() && overlayStreamRemotes.isEmpty()) ->
                Projection(
                    hiddenCategories = categories.map { CustomizeKeys.category(it) }.toSet(),
                    hiddenItems = emptyMap(),
                    categoryOrder = emptyList(),
                )
            // Curated export with no keep-set for this type → hide everything (never "show all").
            curated && effectiveStreamAllow.isEmpty() && categoryAllow.isEmpty() &&
                exportedRemotes.isEmpty() && overlayStreamRemotes.isEmpty() -> Projection(
                hiddenCategories = categories.map { CustomizeKeys.category(it) }.toSet(),
                hiddenItems = emptyMap(),
                categoryOrder = emptyList(),
            )
            effectiveStreamAllow.isEmpty() && categoryAllow.isEmpty() && !curated -> {
                val kept = if (exportedRemotes.isNotEmpty()) {
                    categories.filter { (it.remoteId ?: "") in exportedRemotes }
                } else {
                    emptyList()
                }
                Projection(
                    hiddenCategories = categories.filter { it !in kept }
                        .map { CustomizeKeys.category(it) }.toSet(),
                    hiddenItems = emptyMap(),
                    categoryOrder = orderedKeys,
                )
            }
            else -> {
                // Keep-set must never be empty when an export exists — an empty keep-set hid
                // every Live category (and OwnTV then hides those channels from All too → 0).
                val keptRemotes = linkedSetOf<String>()
                keptRemotes.addAll(categoryAllow)
                keptRemotes.addAll(exportedRemotes)
                keptRemotes.addAll(streamOverlayCats)
                if (effectiveStreamAllow.isNotEmpty()) {
                    addCategoriesForStreams(sourceId, type, effectiveStreamAllow, byId, keptRemotes)
                }
                keptRemotes.removeAll(cpanelCustomRemotes)

                if (keptRemotes.isEmpty()) {
                    // Last resort: show every category still in Room (overlay seed already pruned).
                    categories.mapNotNull { it.remoteId?.takeIf(String::isNotEmpty) }
                        .forEach { keptRemotes.add(it) }
                }

                val hiddenCats = if (keptRemotes.isEmpty()) {
                    emptySet()
                } else {
                    categories
                        .filter { cat ->
                            val remote = cat.remoteId.orEmpty()
                            remote.isEmpty() || remote !in keptRemotes
                        }
                        .map { CustomizeKeys.category(it) }
                        .toSet()
                        // Never hide the entire catalog when exported streams exist.
                        .takeUnless {
                            it.size == categories.size &&
                                (effectiveStreamAllow.isNotEmpty() || overlayStreamRemotes.isNotEmpty())
                        }
                        ?: emptySet()
                }
                val order = orderedKeys.ifEmpty {
                    keptRemotes.mapNotNull { remote ->
                        byRemote[remote]?.let { CustomizeKeys.category(it) }
                    }
                }
                Projection(
                    hiddenCategories = hiddenCats,
                    hiddenItems = buildHiddenItems(
                        sourceId, type, categories, keptRemotes, categoryAllow, effectiveStreamAllow, byId,
                    ),
                    categoryOrder = order,
                )
            }
        }

        val cpanelCustoms = applyCpanelCustomCategories(
            profileId, sourceId, type, snapshot, byRemote,
        )

        customize.update(profileId, type) {
            val keptUserCustoms = it.customCategories.filterNot { c -> isCpanelCustomId(c.id) }
            // Drop previous CPanel move marks; re-apply below from the fresh membership.
            val keptMoved = it.movedFromOrigin.filterKeys { key ->
                key !in cpanelCustoms.movedKeys
            } - cpanelCustoms.movedFromOrigin.keys
            SectionCustomizations(
                hiddenCategories = projection.hiddenCategories,
                hiddenItems = projection.hiddenItems,
                categoryNames = titleByKey,
                itemNames = itemOverlayNames(snapshot.overlays, type, snapshot.mode, sourceId),
                categoryOrder = projection.categoryOrder + cpanelCustoms.customCategories.map { c -> c.id },
                epgMatches = it.epgMatches,
                epgShifts = it.epgShifts,
                customCategories = keptUserCustoms + cpanelCustoms.customCategories,
                movedFromOrigin = keptMoved + cpanelCustoms.movedFromOrigin,
                lockedCategories = it.lockedCategories,
            )
        }
    }

    private data class Projection(
        val hiddenCategories: Set<String>,
        val hiddenItems: Map<String, String>,
        val categoryOrder: List<String>,
    )

    private data class CpanelCustoms(
        val customCategories: List<CustomCategory>,
        val movedFromOrigin: Map<String, String>,
        /** Item keys that previously belonged to a CPanel custom folder (cleared before re-apply). */
        val movedKeys: Set<String>,
    )

    private suspend fun applyCpanelCustomCategories(
        profileId: Long,
        sourceId: Long,
        type: MediaType,
        snapshot: EntitlementSnapshot,
        byRemote: Map<String, CategoryEntity>,
    ): CpanelCustoms {
        val overlayCats = categoryOverlays(snapshot.overlays, type, snapshot.mode)
        val customRemotes = cpanelCustomCategoryRemotes(overlayCats, byRemote, snapshot, type)
        val titleByRemote = overlayCats.associate { it.first to it.second }

        // Stream overlays that point at a custom category remote.
        val typeKey = when (type) {
            MediaType.LIVE -> "live"
            MediaType.MOVIE -> "movie"
            MediaType.SERIES, MediaType.EPISODE -> "series"
        }
        val free = snapshot.mode.equals("free", ignoreCase = true)
        val streamOverlays = snapshot.overlays
            .filter { it.contentType.equals(typeKey, ignoreCase = true) }
            .filter { o ->
                val vis = o.visibility.lowercase()
                when {
                    vis == "hidden" || vis == "none" -> false
                    free && vis == "subscription" -> false
                    else -> true
                }
            }

        val providerCats = categoryDao.observe(listOf(sourceId), type).first().associateBy { it.id }
        val membersByRemote = linkedMapOf<String, MutableList<Pair<String, Long>>>()
        val moved = linkedMapOf<String, String>()

        for (o in streamOverlays) {
            val catRemote = normalizeCategoryRemote(o.categoryExternalId) ?: continue
            if (catRemote !in customRemotes) continue
            val itemRemote = LamassuExportIds.normalize(o.externalId)
            val resolved = resolveItem(sourceId, type, itemRemote, providerCats) ?: continue
            membersByRemote.getOrPut(catRemote) { mutableListOf() }.add(resolved.itemKey to resolved.itemId)
            if (resolved.originKey.isNotBlank()) {
                moved[resolved.itemKey] = resolved.originKey
            }
            customRemotes.add(catRemote)
        }

        // Ensure category overlays with no members yet still create an empty custom folder.
        for (remote in customRemotes) {
            membersByRemote.putIfAbsent(remote, mutableListOf())
        }

        val customs = customRemotes.map { remote ->
            CustomCategory(
                id = cpanelCustomId(remote),
                name = titleByRemote[remote]?.takeIf { it.isNotBlank() } ?: remote,
            )
        }

        // Replace membership for every CPanel custom folder we manage; clear orphans.
        val activeIds = customs.map { it.id }.toSet()
        val previous = customize.observe(profileId, type).first()
        val previousCpanelIds = previous.customCategories.map { it.id }.filter(::isCpanelCustomId)
        for (id in previousCpanelIds) {
            if (id !in activeIds) {
                customCategoryDao.clearContext(profileId, type, id)
            }
        }
        for (cat in customs) {
            val remote = cat.id.removePrefix(CPANEL_CUSTOM_PREFIX)
            val list = membersByRemote[remote].orEmpty()
            val rows = list.mapIndexed { index, (_, itemId) ->
                CustomCategoryMemberEntity(
                    profileId = profileId,
                    mediaType = type,
                    contextKey = cat.id,
                    itemId = itemId,
                    position = index,
                )
            }
            customCategoryDao.replaceContext(profileId, type, cat.id, rows)
        }

        return CpanelCustoms(
            customCategories = customs,
            movedFromOrigin = moved,
            // Re-stamp move marks for every item we place into a CPanel custom folder this pass.
            movedKeys = moved.keys,
        )
    }

    private fun cpanelCustomCategoryRemotes(
        overlayCats: List<Triple<String, String, Int>>,
        byRemote: Map<String, CategoryEntity>,
        snapshot: EntitlementSnapshot,
        type: MediaType,
    ): MutableSet<String> {
        val out = linkedSetOf<String>()
        for ((remote, _, _) in overlayCats) {
            if (isCpanelCustomRemote(remote, byRemote)) out.add(remote)
        }
        val typeKey = when (type) {
            MediaType.LIVE -> "live"
            MediaType.MOVIE -> "movie"
            MediaType.SERIES, MediaType.EPISODE -> "series"
        }
        for (o in snapshot.overlays) {
            if (!o.contentType.equals(typeKey, ignoreCase = true)) continue
            val cat = normalizeCategoryRemote(o.categoryExternalId) ?: continue
            if (isCpanelCustomRemote(cat, byRemote)) out.add(cat)
        }
        return out
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isCpanelCustomRemote(remote: String, byRemote: Map<String, CategoryEntity>): Boolean {
        // Only CPanel-created manual folders — never invent customs for missing Xtream ids.
        return remote.startsWith("manual_", ignoreCase = true)
    }

    private data class ResolvedItem(val itemKey: String, val itemId: Long, val originKey: String)

    private suspend fun resolveItem(
        sourceId: Long,
        type: MediaType,
        remote: String,
        providerCats: Map<Long, CategoryEntity>,
    ): ResolvedItem? {
        fun originKey(categoryId: Long?): String =
            categoryId?.let { providerCats[it] }?.let { CustomizeKeys.category(it) }.orEmpty()

        return when (type) {
            MediaType.LIVE -> {
                val ch = channelDao.findByRemoteIds(sourceId, listOf(remote)).firstOrNull() ?: return null
                ResolvedItem(CustomizeKeys.channel(ch), ch.id, originKey(ch.categoryId))
            }
            MediaType.MOVIE -> {
                val m = movieDao.findByRemoteIds(sourceId, listOf(remote)).firstOrNull() ?: return null
                ResolvedItem(CustomizeKeys.movie(m), m.id, originKey(m.categoryId))
            }
            MediaType.SERIES -> {
                val s = seriesDao.findSeriesByRemote(sourceId, remote) ?: return null
                ResolvedItem(CustomizeKeys.series(s), s.id, originKey(s.categoryId))
            }
            MediaType.EPISODE -> null
        }
    }

    private suspend fun addCategoriesForStreams(
        sourceId: Long,
        type: MediaType,
        streamAllow: Set<String>,
        byId: Map<Long, CategoryEntity>,
        keptRemotes: MutableSet<String>,
    ) {
        when (type) {
            MediaType.LIVE -> streamAllow.toList().chunked(400).forEach { chunk ->
                channelDao.findByRemoteIds(sourceId, chunk).forEach { ch ->
                    byId[ch.categoryId ?: return@forEach]?.remoteId?.let { keptRemotes.add(it) }
                }
            }
            MediaType.MOVIE -> streamAllow.toList().chunked(400).forEach { chunk ->
                movieDao.findByRemoteIds(sourceId, chunk).forEach { m ->
                    byId[m.categoryId ?: return@forEach]?.remoteId?.let { keptRemotes.add(it) }
                }
            }
            MediaType.SERIES -> streamAllow.forEach { remote ->
                val s = seriesDao.findSeriesByRemote(sourceId, remote) ?: return@forEach
                byId[s.categoryId ?: return@forEach]?.remoteId?.let { keptRemotes.add(it) }
            }
            MediaType.EPISODE -> Unit
        }
    }

    private suspend fun buildHiddenItems(
        sourceId: Long,
        type: MediaType,
        categories: List<CategoryEntity>,
        keptRemotes: Set<String>,
        categoryAllow: Set<String>,
        streamAllow: Set<String>,
        byId: Map<Long, CategoryEntity>,
    ): Map<String, String> {
        // AnotherIPTV EntitlementCatalogFilter: when both allowlists empty → show all (handled
        // upstream). When either is set, item passes if streamOk / categoryOk / OR of both.
        if (streamAllow.isEmpty() && categoryAllow.isEmpty()) return emptyMap()
        val keptCatIds = categories.filter { (it.remoteId ?: "") in keptRemotes }.map { it.id }.toSet()
        if (keptCatIds.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()

        fun keep(remote: String, catId: Long): Boolean =
            EntitlementCatalogFilter.itemPasses(
                remoteId = remote,
                categoryRemoteId = byId[catId]?.remoteId,
                streamAllow = streamAllow,
                categoryAllow = categoryAllow,
            )

        when (type) {
            MediaType.LIVE -> {
                channelDao.allForSources(listOf(sourceId), 250_000).forEach { ch ->
                    val catId = ch.categoryId ?: return@forEach
                    if (catId !in keptCatIds) return@forEach
                    val remote = ch.remoteId ?: return@forEach
                    if (!keep(remote, catId)) out[CustomizeKeys.channel(ch)] = ch.name
                }
            }
            MediaType.MOVIE -> {
                for (catId in keptCatIds) {
                    val remotes = movieDao.remoteIdsForCategory(sourceId, catId)
                    val hide = remotes.filter { !keep(it, catId) }
                    if (hide.isEmpty()) continue
                    movieDao.findByRemoteIds(sourceId, hide).forEach { m ->
                        out[CustomizeKeys.movie(m)] = m.name
                    }
                }
            }
            MediaType.SERIES -> {
                for (catId in keptCatIds) {
                    val remotes = seriesDao.remoteIdsForCategory(sourceId, catId)
                    remotes.filter { !keep(it, catId) }.forEach { remote ->
                        val s = seriesDao.findSeriesByRemote(sourceId, remote) ?: return@forEach
                        out[CustomizeKeys.series(s)] = s.name
                    }
                }
            }
            MediaType.EPISODE -> Unit
        }
        return out
    }

    /** (remoteId, title, sortOrder) for exported category overlays of [type]. */
    private fun categoryOverlays(
        overlays: List<ContentOverlayDto>,
        type: MediaType,
        mode: String,
    ): List<Triple<String, String, Int>> {
        val prefix = when (type) {
            MediaType.LIVE -> "live:"
            MediaType.MOVIE -> "movie:"
            MediaType.SERIES, MediaType.EPISODE -> "series:"
        }
        val free = mode.equals("free", ignoreCase = true)
        return overlays
            .asSequence()
            .filter { it.contentType.equals("category", ignoreCase = true) }
            .filter { o ->
                val vis = o.visibility.lowercase()
                when {
                    vis == "hidden" || vis == "none" -> false
                    free && vis == "subscription" -> false
                    else -> true
                }
            }
            .mapNotNull { o ->
                val ext = o.externalId.trim()
                val lower = ext.lowercase()
                val remote = when {
                    lower.startsWith(prefix) -> ext.substringAfter(':').trim()
                    type == MediaType.LIVE &&
                        !lower.startsWith("movie:") &&
                        !lower.startsWith("series:") -> LamassuExportIds.normalize(ext)
                    else -> return@mapNotNull null
                }
                if (remote.isEmpty()) null
                else Triple(remote, o.customTitle.trim(), o.sortOrder ?: Int.MAX_VALUE)
            }
            .sortedBy { it.third }
            .toList()
    }

    private fun itemOverlayNames(
        overlays: List<ContentOverlayDto>,
        type: MediaType,
        mode: String,
        sourceId: Long,
    ): Map<String, String> {
        val typeKey = when (type) {
            MediaType.LIVE -> "live"
            MediaType.MOVIE -> "movie"
            MediaType.SERIES, MediaType.EPISODE -> "series"
        }
        val free = mode.equals("free", ignoreCase = true)
        return overlays
            .filter { it.contentType.equals(typeKey, ignoreCase = true) }
            .filter { o ->
                val vis = o.visibility.lowercase()
                when {
                    vis == "hidden" || vis == "none" -> false
                    free && vis == "subscription" -> false
                    else -> o.customTitle.isNotBlank()
                }
            }
            .associate { o ->
                val remote = LamassuExportIds.normalize(o.externalId)
                "$sourceId:$remote" to o.customTitle.trim()
            }
    }

    private fun streamOverlayRemotes(
        overlays: List<ContentOverlayDto>,
        type: MediaType,
        mode: String,
    ): Set<String> {
        val typeKey = when (type) {
            MediaType.LIVE -> "live"
            MediaType.MOVIE -> "movie"
            MediaType.SERIES, MediaType.EPISODE -> "series"
        }
        val free = mode.equals("free", ignoreCase = true)
        return overlays.asSequence()
            .filter { it.contentType.equals(typeKey, ignoreCase = true) }
            .filter { o ->
                val vis = o.visibility.lowercase().ifBlank { "both" }
                when {
                    vis == "hidden" || vis == "none" -> false
                    free && vis == "subscription" -> false
                    else -> true
                }
            }
            .map { LamassuExportIds.normalize(it.externalId) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun streamOverlayCategoryRemotes(
        overlays: List<ContentOverlayDto>,
        type: MediaType,
        mode: String,
    ): Set<String> {
        val typeKey = when (type) {
            MediaType.LIVE -> "live"
            MediaType.MOVIE -> "movie"
            MediaType.SERIES, MediaType.EPISODE -> "series"
        }
        val free = mode.equals("free", ignoreCase = true)
        return overlays.asSequence()
            .filter { it.contentType.equals(typeKey, ignoreCase = true) }
            .filter { o ->
                val vis = o.visibility.lowercase().ifBlank { "both" }
                when {
                    vis == "hidden" || vis == "none" -> false
                    free && vis == "subscription" -> false
                    else -> true
                }
            }
            .mapNotNull { normalizeCategoryRemote(it.categoryExternalId) }
            .toSet()
    }

    private suspend fun findManaged(provider: ManagedProviderCredentials): tv.own.owntv.core.database.entity.SourceEntity? {
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
            s.type == SourceType.XTREAM && s.name.equals("Lamassu", ignoreCase = true)
        } ?: all.firstOrNull { s -> s.type == SourceType.XTREAM }
    }

    companion object {
        private const val CPANEL_CUSTOM_PREFIX = "custom:cpanel:"

        private fun cpanelCustomId(remote: String): String = CPANEL_CUSTOM_PREFIX + remote

        private fun isCpanelCustomId(id: String): Boolean = id.startsWith(CPANEL_CUSTOM_PREFIX)

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
    }
}
