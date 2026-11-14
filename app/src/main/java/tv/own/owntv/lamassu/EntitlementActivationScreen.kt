package tv.own.owntv.lamassu

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.settings.FirstRunLanguagePopup
import tv.own.owntv.features.settings.LanguageSettingsViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageProgress
import tv.own.owntv.ui.stage.StageSearchField
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import java.text.NumberFormat
import java.util.Locale

private const val DEFAULT_TELEGRAM_URL = "https://t.me/alnafawa"
private const val DEFAULT_TELEGRAM_HANDLE = "@alnafawa"

private val BrandAmber = LamassuBrand.Orange
private val PanelBg = Color(0xF019192A)
private val ScreenTop = LamassuBrand.Navy
private val ScreenMid = Color(0xFF22223A)

@Composable
fun EntitlementActivationScreen(
    gate: EntitlementGateState,
    busy: Boolean,
    errorAr: String?,
    statusAr: String? = null,
    importProgressPercent: Int? = null,
    freeModeEnabled: Boolean = true,
    telegramUrl: String,
    telegramUsername: String = DEFAULT_TELEGRAM_HANDLE,
    subscriptionPrices: List<SubscriptionPricePlan>,
    onActivateFree: () -> Unit,
    onActivateCode: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var showCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var showLanguage by remember { mutableStateOf(false) }
    val languageVm: LanguageSettingsViewModel = koinViewModel()
    val currentTag by languageVm.currentTag.collectAsStateWithLifecycle()
    val selectedLocale = remember(currentTag, languageVm.pickerRows) {
        languageVm.pickerRows.firstOrNull { it.languageTag == currentTag }
    }
    val context = LocalContext.current
    val expired = gate is EntitlementGateState.Expired
    val maintenance = gate is EntitlementGateState.Maintenance
    val plans = remember(subscriptionPrices) { subscriptionPrices.take(4) }
    val telegram = telegramUrl.ifBlank { DEFAULT_TELEGRAM_URL }
    val telegramHandle = telegramUsername.ifBlank { DEFAULT_TELEGRAM_HANDLE }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ScreenTop, ScreenMid, ScreenTop))),
    ) {
        val dense = maxHeight < 480.dp
        val pad = if (dense) 12.dp else 24.dp
        val gap = if (dense) 8.dp else 12.dp

        StageTool(
            text = selectedLocale?.endonym ?: stringResource(R.string.lamassu_language),
            onClick = { showLanguage = true },
            icon = OwnTVIcon.LANGUAGE,
            boxed = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(if (dense) 12.dp else 24.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(pad)
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight - pad * 2)
                .clip(RoundedCornerShape(18.dp))
                .background(PanelBg)
                .padding(
                    horizontal = if (dense) 16.dp else 28.dp,
                    vertical = if (dense) 12.dp else 20.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.lamassu_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(if (dense) 64.dp else 88.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = stageText(if (dense) 26 else 34, 700),
                color = BrandAmber,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = when (gate) {
                    is EntitlementGateState.Maintenance -> gate.messageAr
                    is EntitlementGateState.Expired -> gate.messageAr.ifBlank {
                        stringResource(R.string.lamassu_entitlement_expired)
                    }
                    else -> stringResource(R.string.lamassu_entitlement_welcome)
                },
                style = stageText(if (dense) 14 else 16, 400),
                color = StageColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            if (errorAr != null) {
                Text(
                    text = errorAr,
                    style = stageText(13, 500),
                    color = Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (busy || importProgressPercent != null) {
                ImportProgressBlock(
                    percent = importProgressPercent ?: 0,
                    status = statusAr,
                )
            }

            when {
                maintenance -> {
                    StageButton(
                        text = stringResource(R.string.lamassu_retry),
                        onClick = onRetry,
                        height = 56.mpx,
                        textSize = 20,
                        tinted = true,
                    )
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = true),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            if (showCode) {
                                StageSearchField(
                                    query = code,
                                    onQueryChange = { code = it.uppercase() },
                                    placeholder = stringResource(R.string.lamassu_code_label),
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 54.mpx,
                                    radius = 16.mpx,
                                    autoEdit = true,
                                )
                                StageButton(
                                    text = stringResource(R.string.lamassu_activate),
                                    onClick = {
                                        if (!busy && code.isNotBlank()) onActivateCode(code.trim())
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 56.mpx,
                                    textSize = 20,
                                    tinted = true,
                                )
                                StageButton(
                                    text = stringResource(R.string.lamassu_back),
                                    onClick = { if (!busy) showCode = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 56.mpx,
                                    textSize = 20,
                                )
                            } else {
                                if (freeModeEnabled) {
                                    StageButton(
                                        text = stringResource(R.string.lamassu_free_mode),
                                        onClick = { if (!busy) onActivateFree() },
                                        modifier = Modifier.fillMaxWidth(),
                                        height = 56.mpx,
                                        textSize = 20,
                                        tinted = true,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.lamassu_free_disabled),
                                        style = stageText(13, 400),
                                        color = StageColors.Muted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                StageButton(
                                    text = stringResource(R.string.lamassu_code_title),
                                    onClick = { if (!busy) showCode = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 56.mpx,
                                    textSize = 20,
                                )
                                StageButton(
                                    text = stringResource(R.string.lamassu_telegram),
                                    onClick = {
                                        if (busy) return@StageButton
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(telegram)),
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 56.mpx,
                                    textSize = 20,
                                )
                                Text(
                                    text = telegramHandle,
                                    style = stageText(12, 400),
                                    color = StageColors.Muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.lamassu_prices_title),
                                style = stageText(16, 700),
                                color = BrandAmber,
                            )
                            Text(
                                text = stringResource(R.string.lamassu_prices_subtitle),
                                style = stageText(12, 400),
                                color = StageColors.Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            plans.forEachIndexed { index, plan ->
                                PriceRow(plan = plan, highlight = index == plans.lastIndex)
                            }
                        }
                    }
                }
            }
        }
        ByAlnafawaMark(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (dense) 14.dp else 22.dp),
        )

        if (showLanguage) {
            FirstRunLanguagePopup(
                viewModel = languageVm,
                currentTag = currentTag,
                onDismiss = { showLanguage = false },
            )
        }
    }
}

@Composable
fun EntitlementCheckingScreen() {
    var showLanguage by remember { mutableStateOf(false) }
    val languageVm: LanguageSettingsViewModel = koinViewModel()
    val currentTag by languageVm.currentTag.collectAsStateWithLifecycle()
    val selectedLocale = remember(currentTag, languageVm.pickerRows) {
        languageVm.pickerRows.firstOrNull { it.languageTag == currentTag }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenTop),
        contentAlignment = Alignment.Center,
    ) {
        StageTool(
            text = selectedLocale?.endonym ?: stringResource(R.string.lamassu_language),
            onClick = { showLanguage = true },
            icon = OwnTVIcon.LANGUAGE,
            boxed = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.lamassu_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(20.dp))
            OwnTVSpinner()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.lamassu_checking),
                style = stageText(16, 400),
                color = StageColors.Text,
            )
        }
        ByAlnafawaMark(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp),
        )
        if (showLanguage) {
            FirstRunLanguagePopup(
                viewModel = languageVm,
                currentTag = currentTag,
                onDismiss = { showLanguage = false },
            )
        }
    }
}

@Composable
private fun ByAlnafawaMark(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 132.dp, height = 1.dp)
                .background(BrandAmber.copy(alpha = 0.55f)),
        )
        Text(
            text = stringResource(R.string.lamassu_by_alnafawa),
            style = stageText(12, 500).copy(letterSpacing = 3.2.sp),
            color = BrandAmber.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(width = 132.dp, height = 1.dp)
                .background(BrandAmber.copy(alpha = 0.28f)),
        )
    }
}

@Composable
private fun ImportProgressBlock(percent: Int, status: String?) {
    val p = percent.coerceIn(0, 100)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OwnTVSpinner()
            Text(
                text = stringResource(R.string.lamassu_progress_percent, p),
                style = stageText(18, 700),
                color = BrandAmber,
            )
        }
        StageProgress(
            fraction = p / 100f,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!status.isNullOrBlank()) {
            Text(
                text = status,
                style = stageText(12, 400),
                color = StageColors.Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PriceRow(plan: SubscriptionPricePlan, highlight: Boolean) {
    val iqd = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ar")).format(plan.iqd)
    val usd = NumberFormat.getNumberInstance(Locale.US).format(plan.usd)
    val bg = if (highlight) Color(0x33E8A04A) else Color(0x221A2336)
    val label = plan.labelAr.ifBlank { plan.labelEn }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = stageText(14, if (highlight) 700 else 500),
            color = StageColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.lamassu_price_format, iqd, usd),
            style = stageText(13, 500),
            color = if (highlight) BrandAmber else Color(0xFFC5CBD8),
            maxLines = 1,
        )
    }
}
