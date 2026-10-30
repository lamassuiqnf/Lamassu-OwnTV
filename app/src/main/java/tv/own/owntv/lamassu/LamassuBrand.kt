package tv.own.owntv.lamassu

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.ui.theme.StagePalette

/** Lamassu brand palette — navy base + orange accent as a vertical gradient. */
object LamassuBrand {
    val Navy = Color(0xFF19192A)
    val Orange = Color(0xFFFF9500)
    val OnOrange = Color(0xFF1A1208)
    val AccentHex = "#FF9500"

    val ScreenGradient: Brush
        get() = Brush.verticalGradient(
            colors = listOf(Navy, Color(0xFF22223A), Navy),
        )

    val StageDark: StagePalette = StagePalette.Dark.copy(
        page = Navy,
        glassTint = Color(0xFF22223A),
        glassOff = Color(0xFF1E1E32),
        wash = Navy,
        scrim = Navy.copy(alpha = 0.72f),
        warn = Orange,
        chipWarnText = Orange,
        chipWarnFill = Orange.copy(alpha = 0.18f),
    )

    /** Sections removed from the Lamassu TV shell. */
    val HiddenNavSections: Set<String> = setOf(
        MainSection.SEARCH.name,
        MainSection.SPORTS.name,
        MainSection.EPG.name,
        MainSection.DOWNLOADS.name,
    )

    val RailOrder: List<MainSection> = listOf(
        MainSection.HOME,
        MainSection.LIVE_TV,
        MainSection.MOVIES,
        MainSection.SERIES,
    )
}
