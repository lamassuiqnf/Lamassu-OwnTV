package tv.own.owntv.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import tv.own.owntv.R
import tv.own.owntv.core.theme.AppFontFamily

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variableFont(resourceId: Int, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    Font(
        resourceId,
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

private val LoraFamily = FontFamily(
    variableFont(R.font.lora_variable, FontWeight.Normal),
    variableFont(R.font.lora_variable, FontWeight.Medium),
    variableFont(R.font.lora_variable, FontWeight.SemiBold),
    variableFont(R.font.lora_variable, FontWeight.Bold),
    variableFont(R.font.lora_italic_variable, FontWeight.Normal, FontStyle.Italic),
    variableFont(R.font.lora_italic_variable, FontWeight.Bold, FontStyle.Italic),
)

private val PlayfairDisplayFamily = FontFamily(
    variableFont(R.font.playfair_display_variable, FontWeight.Normal),
    variableFont(R.font.playfair_display_variable, FontWeight.Medium),
    variableFont(R.font.playfair_display_variable, FontWeight.SemiBold),
    variableFont(R.font.playfair_display_variable, FontWeight.Bold),
    variableFont(R.font.playfair_display_italic_variable, FontWeight.Normal, FontStyle.Italic),
    variableFont(R.font.playfair_display_italic_variable, FontWeight.Bold, FontStyle.Italic),
)

private val DancingScriptFamily = FontFamily(
    variableFont(R.font.dancing_script_variable, FontWeight.Normal),
    variableFont(R.font.dancing_script_variable, FontWeight.Medium),
    variableFont(R.font.dancing_script_variable, FontWeight.SemiBold),
    variableFont(R.font.dancing_script_variable, FontWeight.Bold),
)

// Stage's typeface: every weight it uses (400–800) from one variable file.
private val PlusJakartaSansFamily = FontFamily(
    variableFont(R.font.plus_jakarta_sans_variable, FontWeight.Normal),
    variableFont(R.font.plus_jakarta_sans_variable, FontWeight.Medium),
    variableFont(R.font.plus_jakarta_sans_variable, FontWeight.SemiBold),
    variableFont(R.font.plus_jakarta_sans_variable, FontWeight.Bold),
    variableFont(R.font.plus_jakarta_sans_variable, FontWeight.ExtraBold),
)

private val PoppinsFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

/** Lamassu default UI typeface (bein Normal). */
private val BeinNormalFamily = FontFamily(
    Font(R.font.bein_normal, FontWeight.Normal),
    Font(R.font.bein_normal, FontWeight.Medium),
    Font(R.font.bein_normal, FontWeight.SemiBold),
    Font(R.font.bein_normal, FontWeight.Bold),
    Font(R.font.bein_normal, FontWeight.ExtraBold),
)

fun AppFontFamily.asComposeFamily(): FontFamily = when (this) {
    AppFontFamily.LORA -> LoraFamily
    // Lamassu: SYSTEM_SANS is forced as the product default and maps to bein.
    AppFontFamily.SYSTEM_SANS -> BeinNormalFamily
    AppFontFamily.MONOSPACE -> FontFamily.Monospace
    AppFontFamily.PLAYFAIR_DISPLAY -> PlayfairDisplayFamily
    AppFontFamily.DANCING_SCRIPT -> DancingScriptFamily
    AppFontFamily.POPPINS -> PoppinsFamily
    AppFontFamily.PLUS_JAKARTA_SANS -> BeinNormalFamily
}

/** Android/mpv equivalents used by subtitle renderers outside Compose typography. */
fun AppFontFamily.asAndroidTypeface(context: android.content.Context): android.graphics.Typeface = when (this) {
    AppFontFamily.MONOSPACE -> android.graphics.Typeface.MONOSPACE
    else -> androidx.core.content.res.ResourcesCompat.getFont(context, subtitleFontResource)
        ?: android.graphics.Typeface.SANS_SERIF
}

val AppFontFamily.subtitleFontResource: Int
    get() = when (this) {
        AppFontFamily.LORA -> R.font.lora_variable
        AppFontFamily.PLAYFAIR_DISPLAY -> R.font.playfair_display_variable
        AppFontFamily.DANCING_SCRIPT -> R.font.dancing_script_variable
        AppFontFamily.POPPINS -> R.font.poppins_regular
        AppFontFamily.PLUS_JAKARTA_SANS,
        AppFontFamily.SYSTEM_SANS,
        -> R.font.bein_normal
        AppFontFamily.MONOSPACE -> 0
    }

val LocalMainFontFamily = staticCompositionLocalOf<FontFamily> { BeinNormalFamily }
val LocalPopupFontFamily = staticCompositionLocalOf<FontFamily> { BeinNormalFamily }
val LocalUiFontScaleFactor = staticCompositionLocalOf { 1f }
val LocalPopupFontScaleFactor = staticCompositionLocalOf { 1f }
val LocalPopupSizeScaleFactor = staticCompositionLocalOf { 1f }
