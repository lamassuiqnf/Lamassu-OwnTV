package tv.own.owntv.lamassu

/**
 * Effective CPanel export gate for one entitlement mode.
 *
 * Source of truth = enabled CPanel overlays (+ server allowlists from publish), matching
 * AnotherIPTV's EntitlementAccess + EntitlementCatalogFilter inputs.
 * Server lists and overlay-derived lists are merged so a partial publish still filters.
 */
internal data class LamassuExportPermissions(
    val allowLive: Boolean,
    val allowMovies: Boolean,
    val allowSeries: Boolean,
    val liveCategoryAllowlist: List<String>,
    val liveStreamAllowlist: List<String>,
    val movieCategoryAllowlist: List<String>,
    val movieStreamAllowlist: List<String>,
    val seriesCategoryAllowlist: List<String>,
    val seriesStreamAllowlist: List<String>,
    /** True when CPanel published a curated selection (overlays and/or __none__). */
    val curated: Boolean,
) {
    companion object {
        fun resolve(snapshot: EntitlementSnapshot): LamassuExportPermissions {
            val derived = deriveFromOverlays(snapshot.overlays, snapshot.mode)
            val p = snapshot.permissions
            val serverCurated = listOf(
                p.liveCategoryAllowlist, p.liveStreamAllowlist,
                p.movieCategoryAllowlist, p.movieStreamAllowlist,
                p.seriesCategoryAllowlist, p.seriesStreamAllowlist,
            ).any { it.isNotEmpty() }
            val curated = derived.curated || serverCurated

            if (!serverCurated) return derived.copy(curated = curated)

            // allow_* flags come from the server publish. Allowlist IDs are merged with
            // overlay rows so selective stream exports always filter browse.
            return LamassuExportPermissions(
                allowLive = p.allowLive,
                allowMovies = p.allowMovies,
                allowSeries = p.allowSeries,
                liveCategoryAllowlist = mergeLists(p.liveCategoryAllowlist, derived.liveCategoryAllowlist),
                liveStreamAllowlist = mergeLists(p.liveStreamAllowlist, derived.liveStreamAllowlist),
                movieCategoryAllowlist = mergeLists(p.movieCategoryAllowlist, derived.movieCategoryAllowlist),
                movieStreamAllowlist = mergeLists(p.movieStreamAllowlist, derived.movieStreamAllowlist),
                seriesCategoryAllowlist = mergeLists(p.seriesCategoryAllowlist, derived.seriesCategoryAllowlist),
                seriesStreamAllowlist = mergeLists(p.seriesStreamAllowlist, derived.seriesStreamAllowlist),
                curated = curated,
            )
        }

        private fun mergeLists(server: List<String>, overlay: List<String>): List<String> {
            if (server.isEmpty()) return overlay
            if (overlay.isEmpty()) return server
            val serverOnlyNone = server.all { it.trim() == LamassuExportIds.NONE }
            val overlayOnlyNone = overlay.all { it.trim() == LamassuExportIds.NONE }
            // Explicit panel close (server is only __none__).
            if (serverOnlyNone) return listOf(LamassuExportIds.NONE)
            // Do NOT let overlay-derived __none__ wipe a real server allowlist — that hid Live.
            if (overlayOnlyNone) {
                return server.map { it.trim() }.filter { it.isNotEmpty() && it != LamassuExportIds.NONE }
            }
            if (LamassuExportIds.hasNone(server) && LamassuExportIds.hasNone(overlay)) {
                return listOf(LamassuExportIds.NONE)
            }
            val out = linkedSetOf<String>()
            out.addAll(server.map { it.trim() }.filter { it.isNotEmpty() && it != LamassuExportIds.NONE })
            out.addAll(overlay.map { it.trim() }.filter { it.isNotEmpty() && it != LamassuExportIds.NONE })
            return out.toList()
        }

        /** Mirrors CPanel publish rules: curated overlays → allowlists; empty → closed for free. */
        fun deriveFromOverlays(overlays: List<ContentOverlayDto>, mode: String): LamassuExportPermissions {
            val free = mode.equals("free", ignoreCase = true)
            val liveCats = linkedSetOf<String>()
            val liveStreams = linkedSetOf<String>()
            val movieCats = linkedSetOf<String>()
            val movieStreams = linkedSetOf<String>()
            val seriesCats = linkedSetOf<String>()
            val seriesStreams = linkedSetOf<String>()

            for (o in overlays) {
                val vis = o.visibility.lowercase().ifBlank { "both" }
                if (free && vis == "subscription") continue
                if (vis == "hidden" || vis == "none") continue
                val ext = o.externalId.trim()
                if (ext.isEmpty()) continue
                when (o.contentType.trim().lowercase()) {
                    "category" -> {
                        val lower = ext.lowercase()
                        when {
                            lower.startsWith("live:") -> liveCats.add(ext.substringAfter(':').trim())
                            lower.startsWith("movie:") -> movieCats.add(ext.substringAfter(':').trim())
                            lower.startsWith("series:") -> seriesCats.add(ext.substringAfter(':').trim())
                        }
                    }
                    "live" -> liveStreams.add(LamassuExportIds.normalize(ext))
                    "movie" -> movieStreams.add(LamassuExportIds.normalize(ext))
                    "series" -> seriesStreams.add(LamassuExportIds.normalize(ext))
                }
            }

            val hasLive = liveCats.isNotEmpty() || liveStreams.isNotEmpty()
            val hasMovies = movieCats.isNotEmpty() || movieStreams.isNotEmpty()
            val hasSeries = seriesCats.isNotEmpty() || seriesStreams.isNotEmpty()
            val curated = hasLive || hasMovies || hasSeries
            val none = listOf(LamassuExportIds.NONE)

            // Managed Lamassu: when curated, missing types are closed (__none__).
            // When not curated, free stays closed; paid-without-export stays closed too
            // (never treat "no export" as "show entire Xtream dump").
            return LamassuExportPermissions(
                allowLive = if (curated) hasLive else false,
                allowMovies = if (curated) hasMovies else false,
                allowSeries = if (curated) hasSeries else false,
                liveCategoryAllowlist = when {
                    liveCats.isNotEmpty() -> liveCats.toList()
                    curated -> none
                    else -> emptyList()
                },
                liveStreamAllowlist = liveStreams.toList(),
                movieCategoryAllowlist = when {
                    movieCats.isNotEmpty() -> movieCats.toList()
                    curated -> none
                    else -> emptyList()
                },
                movieStreamAllowlist = movieStreams.toList(),
                seriesCategoryAllowlist = when {
                    seriesCats.isNotEmpty() -> seriesCats.toList()
                    curated -> none
                    else -> emptyList()
                },
                seriesStreamAllowlist = seriesStreams.toList(),
                curated = curated,
            )
        }
    }
}
