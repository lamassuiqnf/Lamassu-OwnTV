package tv.own.owntv.features.shell.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.settings.ClockColors
import tv.own.owntv.core.weather.WeatherInfo
import androidx.compose.runtime.staticCompositionLocalOf
import tv.own.owntv.ui.theme.parseAccentHex
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.components.OwnTVIcon
import java.util.Date

/** The Continue pill's content: the icon, the small action label ("Resume") and the title. */
data class ContinuePill(val icon: OwnTVIcon, val iconFilled: Boolean, val action: String, val title: String, val onClick: () -> Unit)

/**
 * Lamassu status mark: time + date (+ weather) only — no floating brand/playlist pill.
 * Anchored bottom-end by the shell so it stays clear of banners, headers and list rows.
 *
 * [audioBar] (Audio mode) sits above the clock in the same corner so it does not cover Home banners.
 * Unused playlist/continue parameters are kept so call sites stay stable.
 */
@Composable
fun StageStatusCluster(
    continuePill: ContinuePill?,
    audioBar: (@Composable () -> Unit)?,
    playlistLabel: String,
    playlistInteractive: Boolean,
    onPlaylistClick: () -> Unit,
    pillsFocusable: Boolean,
    weatherInfo: WeatherInfo?,
    weatherFahrenheit: Boolean,
    clockColors: ClockColors,
    modifier: Modifier = Modifier,
    playlistDownFocusRequester: FocusRequester? = null,
    onPlaylistPillBounds: (Rect) -> Unit = {},
    showClock: Boolean = true,
) {
    // Brand / continue / playlist pills removed for Lamassu; keep the parameters so OwnTVShell
    // call sites stay unchanged. Touch the unused args so the compiler keeps the API honest.
    if (false) {
        continuePill?.onClick?.invoke()
        onPlaylistClick()
        onPlaylistPillBounds(Rect.Zero)
        playlistDownFocusRequester?.requestFocus()
        if (playlistInteractive && pillsFocusable) playlistLabel.length
    }
    if (!showClock && audioBar == null) return
    Column(
        modifier = modifier.padding(end = 48.mpx, bottom = 40.mpx),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(14.mpx),
    ) {
        audioBar?.invoke()
        if (showClock) StageClock(weatherInfo, weatherFahrenheit, clockColors)
    }
}

/** The shell's weather and °F choice, for the clock preview on Settings › Date, time & weather. */
val LocalClockWeather = staticCompositionLocalOf<Pair<WeatherInfo?, Boolean>> { null to false }

/** A stored clock colour: blank = [default], "accent" = the accent, else the hex code. */
@Composable
fun clockColor(stored: String, default: Color): Color = when (stored) {
    "" -> default
    ClockColors.ACCENT -> stageAccent.accent
    else -> parseAccentHex(stored) ?: default
}

/**
 * Time (tabular) with date · weather under it, right-aligned. Colours from
 * Settings › Appearance › Date, time & weather.
 */
@Composable
fun StageClock(weatherInfo: WeatherInfo?, fahrenheit: Boolean, colors: ClockColors) {
    val timeColor = clockColor(colors.time, StageColors.Text)
    val dateColor = clockColor(colors.date, StageColors.Muted)
    val weatherColor = clockColor(colors.weather, StageColors.Muted)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val time = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    val date = remember(now, locale) {
        android.text.format.DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), now).toString()
    }
    // Soft lift off backdrops. Light theme uses a small white chip instead of a dark glow.
    val light = LocalStagePalette.current.isLight
    val rim = StageColors.Ink.copy(alpha = 0.07f)
    val shadow = if (light) null else with(LocalDensity.current) { Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.mpx.toPx()), 3.mpx.toPx()) }
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.drawBehind {
            if (light) {
                val dx = 16.mpx.toPx()
                val dy = 10.mpx.toPx()
                val cr = androidx.compose.ui.geometry.CornerRadius(18.mpx.toPx())
                val tl = Offset(-dx, -dy)
                val sz = androidx.compose.ui.geometry.Size(size.width + 2 * dx, size.height + 2 * dy)
                drawRoundRect(Color.White.copy(alpha = 0.74f), tl, sz, cr)
                drawRoundRect(rim, tl, sz, cr, style = androidx.compose.ui.graphics.drawscope.Stroke(1.mpx.toPx()))
                return@drawBehind
            }
            val r = size.height * 1.2f

            scale(scaleX = (size.width * 0.8f) / r, scaleY = 1f, pivot = center) {
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.34f),
                        0.55f to Color.Black.copy(alpha = 0.16f),
                        1f to Color.Transparent,
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )
            }
        },
    ) {
        Text(
            time,
            style = stageText(34, 700, (-0.5).mpxSp).copy(fontFeatureSettings = "tnum", shadow = shadow, lineHeight = 34.mpxSp),
            color = timeColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.padding(top = 4.mpx),
            horizontalArrangement = Arrangement.spacedBy(10.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sub: TextStyle = stageText(15, 400).copy(shadow = shadow)
            Text(date, style = sub, color = dateColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (weatherInfo != null) {
                Text("·", style = sub, color = StageColors.Muted.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                WeatherConditionIcon(weatherInfo, Modifier.size(15.mpx))
                val degrees = if (fahrenheit) (weatherInfo.temperatureC * 9 / 5 + 32).toInt() else weatherInfo.temperatureC.toInt()
                Text(
                    if (weatherInfo.city.isNotBlank()) {
                        stringResource(R.string.common_weather_degrees_city, degrees, weatherInfo.city)
                    } else {
                        stringResource(R.string.common_weather_degrees_city, degrees, "").trim()
                    },
                    style = sub,
                    color = weatherColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
