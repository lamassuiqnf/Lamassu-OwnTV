package tv.own.owntv.lamassu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Paid-code status in Settings (Quick): shown only when the user activated with a subscription code.
 */
@Composable
fun LamassuSubscriptionSettingsCard(modifier: Modifier = Modifier) {
    val entitlement: EntitlementCoordinator = koinInject()
    val snap by entitlement.snapshot.collectAsStateWithLifecycle()
    val gate by entitlement.gateState.collectAsStateWithLifecycle()
    val mode = snap?.mode?.lowercase().orEmpty()
    if (mode != "subscription" && gate !is EntitlementGateState.Expired) return

    val accent = stageAccent.accent
    val expired = gate is EntitlementGateState.Expired ||
        snap?.status?.equals("expired", ignoreCase = true) == true
    val title = if (expired) {
        stringResource(R.string.lamassu_entitlement_expired)
    } else {
        stringResource(R.string.lamassu_code_activated)
    }
    val expires = snap?.expiresAt?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.lamassu_sub_expires_none)
    val code = snap?.codePrefix?.takeIf { it.isNotBlank() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.mpx),
        verticalArrangement = Arrangement.spacedBy(6.mpx),
    ) {
        Text(stringResource(R.string.lamassu_sub_title), style = stageText(15, 700), color = StageColors.Dim)
        Text(title, style = stageText(22, 800), color = if (expired) StageColors.Muted else accent)
        Text(
            stringResource(
                R.string.lamassu_sub_status,
                snap?.status?.ifBlank { null } ?: stringResource(R.string.lamassu_sub_unknown),
            ),
            style = stageText(16, 500),
            color = StageColors.Muted,
        )
        Text(stringResource(R.string.lamassu_sub_expires, expires), style = stageText(16, 500), color = StageColors.Muted)
        if (code != null) {
            Text(stringResource(R.string.lamassu_sub_code, code), style = stageText(16, 500), color = StageColors.Muted)
        }
    }
}
