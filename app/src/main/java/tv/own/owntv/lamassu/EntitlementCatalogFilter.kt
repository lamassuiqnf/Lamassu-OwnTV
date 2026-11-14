package tv.own.owntv.lamassu

/**
 * Export item-pass rules for OwnTV's Room + CustomizationStore projection.
 *
 * AnotherIPTV's UI filter uses `streamOk || categoryOk` when both lists are set, which is safe
 * there because browse lists are overlay-merged on top of (often empty) Xtream Room rows.
 * OwnTV may briefly hold a fuller Xtream dump during background enrichment, so when a stream
 * allowlist is present we require stream membership — same exported set CPanel enabled, without
 * leaking sibling channels that only share an allowlisted category.
 */
internal object EntitlementCatalogFilter {

    fun itemPasses(
        remoteId: String,
        categoryRemoteId: String?,
        streamAllow: Set<String>,
        categoryAllow: Set<String>,
    ): Boolean {
        val streamOk = streamAllow.isEmpty() ||
            streamAllow.contains(LamassuExportIds.normalize(remoteId))
        val cat = categoryRemoteId?.let(LamassuExportIds::normalize).orEmpty()
        val categoryOk = categoryAllow.isEmpty() || (cat.isNotEmpty() && cat in categoryAllow)
        return when {
            streamAllow.isNotEmpty() -> streamOk
            categoryAllow.isNotEmpty() -> categoryOk
            else -> true
        }
    }
}
