package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import tv.own.owntv.core.i18n.HorizontalDirection
import tv.own.owntv.core.i18n.horizontalDirection

/**
 * Toward-start (◀ in LTR, ▶ in RTL) runs [action] while [enabled], before the focused node sees it.
 * Used when that direction opens categories / a side panel a focus search would not find.
 */
@Composable
fun Modifier.onDpadLeft(enabled: () -> Boolean, action: () -> Unit): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    return onPreviewKeyEvent { e ->
        e.type == KeyEventType.KeyDown &&
            e.key.horizontalDirection(layoutDirection) == HorizontalDirection.START &&
            enabled() &&
            run { action(); true }
    }
}
