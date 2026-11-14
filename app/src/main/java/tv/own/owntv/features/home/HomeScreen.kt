package tv.own.owntv.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.lamassu.LamassuHomeBanners
import tv.own.owntv.ui.theme.mpx

/** Screen-top offset so banners sit below the status cluster. */
private val NoHeroTop = 110.mpx

/**
 * Lamassu Home: CPanel banners only (no recently watched / continue / trending rows).
 * Signature stays compatible with [tv.own.owntv.features.shell.OwnTVShell].
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onPlayMovie: (movieId: Long, positionMs: Long) -> Unit,
    onPlayEpisode: (seriesId: Long, episodeId: Long, positionMs: Long) -> Unit,
    onPlayChannel: (channelId: Long, zapChannels: List<ChannelEntity>) -> Unit,
    onActivateTrending: (TrendingHomeItem, onUnavailable: () -> Unit) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onOpenSeries: (SeriesEntity) -> Unit,
    onOpenTrendingSearch: (String) -> Unit,
    onChildFocused: () -> Unit,
    restoreFocus: Boolean = false,
    restoreTrendingSearchFocus: Boolean = false,
    onRestored: () -> Unit = {},
    previewEnabled: Boolean = true,
    firstRowFocusRequester: FocusRequester? = null,
    contentStart: Dp = 150.mpx,
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    onOpenMatch: (tv.own.owntv.core.sports.model.Match) -> Unit = {},
    modifier: Modifier = Modifier,
    sportsVm: tv.own.owntv.features.sports.SportsViewModel = org.koin.androidx.compose.koinViewModel(),
) {
    // Keep shell wiring valid; Home no longer drives continue/trending playback from this screen.
    listOf(
        vm, onPlayMovie, onPlayEpisode, onPlayChannel, onActivateTrending, onOpenMovie, onOpenSeries,
        onOpenTrendingSearch, onChildFocused, previewEnabled, firstRowFocusRequester, onOpenMatch, sportsVm,
    )
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke { false }
        onDispose { onEntryHook?.invoke(null) }
    }
    LaunchedEffect(restoreFocus, restoreTrendingSearchFocus) {
        if (restoreFocus || restoreTrendingSearchFocus) onRestored()
    }
    Box(modifier.fillMaxSize()) {
        LamassuHomeBanners(
            contentStart = contentStart,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = NoHeroTop),
        )
    }
}

/**
 * Reports this control when it takes focus, as the place ▶ out of the rail returns to. Pass the
 * control's own [requester] when it already has one; otherwise one is made for it.
 */
@Composable
internal fun Modifier.tracked(requester: FocusRequester? = null, track: (FocusRequester) -> Unit): Modifier {
    val own = remember { FocusRequester() }
    return (if (requester == null) focusRequester(own) else this)
        .onFocusChanged { if (it.hasFocus) track(requester ?: own) }
}

@Composable
internal fun relativeLastWatchedLabel(lastEngagementAt: Long, nowMs: Long): String {
    val elapsedMs = nowMs - lastEngagementAt
    if (elapsedMs < 60_000L) return stringResource(R.string.home_last_watched_now)

    val elapsedMinutes = elapsedMs / 60_000L
    if (elapsedMinutes < 60L) {
        return pluralStringResource(R.plurals.home_last_watched_minutes, elapsedMinutes.toInt(), elapsedMinutes.toInt())
    }

    val elapsedHours = elapsedMinutes / 60L
    if (elapsedHours < 24L) {
        return pluralStringResource(R.plurals.home_last_watched_hours, elapsedHours.toInt(), elapsedHours.toInt())
    }

    val elapsedDays = elapsedHours / 24L
    return pluralStringResource(R.plurals.home_last_watched_days, elapsedDays.toInt(), elapsedDays.toInt())
}
