package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales
import java.util.Locale

/**
 * Thin ViewModel for the in-app language picker. [LocaleStore] owns persistence and process-level
 * locale application; this only exposes the current tag, English-name-sorted picker rows, and a write entry point.
 */
class LanguageSettingsViewModel(
    private val localeStore: LocaleStore,
) : ViewModel() {

    val currentTag: StateFlow<String> = localeStore.currentTag

    /** Lamassu ships Arabic + English only. System default is not in this list. */
    val pickerRows: List<SupportedLocale> =
        SupportedLocales.pickerRows
            .filter { it.languageTag == "ar" || it.languageTag.startsWith("en") }
            .sortedBy { it.englishName.lowercase(Locale.ROOT) }

    fun setLocale(tag: String) {
        viewModelScope.launch { localeStore.set(tag) }
    }
}
