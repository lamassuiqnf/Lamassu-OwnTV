package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.setup.displayText
import tv.own.owntv.core.util.FriendlySyncFailure

/** Resolve a classified sync failure only at the Compose presentation boundary; the sentences are core's. */
@Composable
fun FriendlySyncFailure.displayText(): String {
    // Lamassu: never show Unknown raw Xtream/CPanel exception text.
    if (this is FriendlySyncFailure.Unknown) {
        return stringResource(R.string.lamassu_sync_failed_clean)
    }
    return displayText(LocalContext.current.resources)
}
