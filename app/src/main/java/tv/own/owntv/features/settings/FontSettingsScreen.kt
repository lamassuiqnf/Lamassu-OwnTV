package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.PopupFontScale
import tv.own.owntv.core.theme.UiFontScale
import tv.own.owntv.ui.components.OwnTVIcon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Settings › Appearance › Fonts & text size as a Stage page (no popup for simple values).
 * Font (its list in the panel), Text size and Popup text size (◀ ▶ in place, OK opens the value in the
 * panel — popup text size with a real-size sample popup), then Reset. Each change applies at once.
 */
@Composable
fun FontSettingsScreen(
    current: FontCustomization,
    onSet: (FontCustomization) -> Unit,
    familyLabel: @Composable (AppFontFamily) -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<FontEdit?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val percent: @Composable (Int) -> String = { stringResource(R.string.common_percent, it) }
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_appearance)),
        // The row's own name for it ("Fonts & text size"), so the page is called what opened it.
        title = settingWords("fonts", stringResource(R.string.settings_font_customization), null).title,
        count = pluralStringResource(R.plurals.settings_setting_count, 4, 4),
        onBack = onBack,
        modifier = modifier,
    ) {
        // Lamassu: font family is fixed to system "normal" sans; only sizing is editable.
        val sizeTitle = stringResource(R.string.settings_font_size)
        val sizeValue = SettingValue.Stepper(percent(current.sizePercent))
        val stepSize = { d: Int ->
            onSet(
                current.copy(
                    sizePercent = UiFontScale.clamp(current.sizePercent + d * UiFontScale.STEP),
                    mainFamily = AppFontFamily.SYSTEM_SANS,
                    popupFamily = AppFontFamily.SYSTEM_SANS,
                ),
            )
        }
        StageSettingRow(
            icon = OwnTVIcon.TEXT_SIZE,
            title = sizeTitle,
            desc = stringResource(R.string.settings_font_size_range, UiFontScale.MIN, UiFontScale.MAX),
            value = sizeValue,
            onClick = { editing = FontEdit.SIZE },
            onStep = stepSize,
            help = settingHelp(null, sizeTitle, stringResource(R.string.settings_font_size_range, UiFontScale.MIN, UiFontScale.MAX), sizeValue, pinnable = false),
            modifier = Modifier.focusRequester(first),
        )
        val popupTitle = stringResource(R.string.settings_popup_font_size)
        val popupValue = SettingValue.Stepper(percent(current.popupFontSizePercent))
        val stepPopup = { d: Int ->
            onSet(
                current.copy(
                    popupFontSizePercent = PopupFontScale.clamp(current.popupFontSizePercent + d * PopupFontScale.STEP),
                    mainFamily = AppFontFamily.SYSTEM_SANS,
                    popupFamily = AppFontFamily.SYSTEM_SANS,
                ),
            )
        }
        StageSettingRow(
            icon = OwnTVIcon.TEXT_SIZE,
            title = popupTitle,
            desc = stringResource(R.string.settings_popup_font_size_description),
            value = popupValue,
            onClick = { editing = FontEdit.POPUP },
            onStep = stepPopup,
            help = settingHelp(null, popupTitle, stringResource(R.string.settings_popup_font_size_description), popupValue, pinnable = false),
        )
        val reset = stringResource(R.string.common_reset)
        StageSettingRow(
            icon = OwnTVIcon.REFRESH,
            title = reset,
            desc = null,
            value = null,
            onClick = {
                onSet(
                    FontCustomization(
                        popupSizePercent = current.popupSizePercent,
                        mainFamily = AppFontFamily.SYSTEM_SANS,
                        popupFamily = AppFontFamily.SYSTEM_SANS,
                    ),
                )
            },
            help = SettingHelp(reset, stringResource(R.string.settings_font_customization_description), hints = settingHints(null, pinnable = false)),
        )

        when (editing) {
            FontEdit.SIZE -> panelEditor({ editing = null }) {
                PanelStepper(
                    percent(current.sizePercent),
                    onStep = stepSize,
                    onReset = {
                        onSet(
                            current.copy(
                                sizePercent = UiFontScale.DEFAULT,
                                mainFamily = AppFontFamily.SYSTEM_SANS,
                                popupFamily = AppFontFamily.SYSTEM_SANS,
                            ),
                        )
                    },
                    onDone = { editing = null },
                )
            }
            FontEdit.POPUP -> panelEditor({ editing = null }) {
                PanelStepper(
                    percent(current.popupFontSizePercent),
                    onStep = stepPopup,
                    onReset = {
                        onSet(
                            current.copy(
                                popupFontSizePercent = PopupFontScale.DEFAULT,
                                mainFamily = AppFontFamily.SYSTEM_SANS,
                                popupFamily = AppFontFamily.SYSTEM_SANS,
                            ),
                        )
                    },
                    onDone = { editing = null },
                    preview = { PopupSizeSample(current.popupSizePercent) },
                )
            }
            null -> Unit
        }
    }
}

private enum class FontEdit { SIZE, POPUP }
