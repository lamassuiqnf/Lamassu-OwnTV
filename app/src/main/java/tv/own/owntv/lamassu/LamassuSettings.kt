package tv.own.owntv.lamassu

import tv.own.owntv.features.shell.components.SettingsGroup

/**
 * Lamassu product surface for Settings: Quick + Appearance (sizing only).
 * Group ordinals stay aligned with [SettingsGroup] so More cards open the right panel.
 */
internal object LamassuSettings {
    val VisibleGroups: Set<SettingsGroup> = setOf(
        SettingsGroup.QUICK,
        SettingsGroup.APPEARANCE,
    )

    val VisibleGroupOrdinals: Set<Int> = VisibleGroups.map { it.ordinal }.toSet()

    /** Appearance rows: text size + popup size + UI zoom (font family stays bein). */
    val AppearanceRowKeys: Set<String> = setOf("fonts", "popup_size", "ui_zoom")

    /** Quick pins without check-for-updates. */
    val QuickPinnedKeys: List<String> = listOf(
        "quick_live_preview",
        "quick_preview_sound",
        "quick_channel_numbers",
        "quick_hdr",
        "quick_autoplay",
    )

    fun isVisibleGroup(ordinal: Int): Boolean = ordinal in VisibleGroupOrdinals
}
