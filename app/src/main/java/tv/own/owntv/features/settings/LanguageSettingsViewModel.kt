package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales

/**
 * Thin ViewModel for the in-app language picker. [LocaleStore] owns persistence and process-level
 * locale application; this only exposes the current tag, sorted picker rows, and a write entry point.
 */
class LanguageSettingsViewModel(
    private val localeStore: LocaleStore,
) : ViewModel() {

    val currentTag: StateFlow<String> = localeStore.currentTag

    /** Packaged + picker-visible catalogue rows, A–Z by endonym. System default is not in this list. */
    val pickerRows: List<SupportedLocale> =
        SupportedLocales.pickerRows.sortedBy { it.endonym.lowercase() }

    fun setLocale(tag: String) {
        viewModelScope.launch { localeStore.set(tag) }
    }
}
