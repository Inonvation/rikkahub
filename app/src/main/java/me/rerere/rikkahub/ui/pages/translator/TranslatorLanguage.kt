package me.rerere.rikkahub.ui.pages.translator

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import java.util.Locale

/**
 * 翻译器支持的（下拉可选的）语言列表。
 * 与历史记录显示共用的唯一来源：新增语言时只需改这里。
 */
internal val TranslatorSupportedLocales = listOf(
    Locale.SIMPLIFIED_CHINESE,
    Locale.ENGLISH,
    Locale.TRADITIONAL_CHINESE,
    Locale.JAPANESE,
    Locale.KOREAN,
    Locale.FRENCH,
    Locale.GERMAN,
    Locale.ITALIAN,
    Locale("es", "ES")
)

@Composable
internal fun localeDisplayName(locale: Locale): String {
    return when (locale) {
        Locale.SIMPLIFIED_CHINESE -> stringResource(R.string.language_simplified_chinese)
        Locale.ENGLISH -> stringResource(R.string.language_english)
        Locale.TRADITIONAL_CHINESE -> stringResource(R.string.language_traditional_chinese)
        Locale.JAPANESE -> stringResource(R.string.language_japanese)
        Locale.KOREAN -> stringResource(R.string.language_korean)
        Locale.FRENCH -> stringResource(R.string.language_french)
        Locale.GERMAN -> stringResource(R.string.language_german)
        Locale.ITALIAN -> stringResource(R.string.language_italian)
        Locale("es", "ES") -> stringResource(R.string.language_spanish)
        // 语言列表之外的兜底（如恢复早期/外部写入的记录）
        else -> locale.getDisplayLanguage(Locale.getDefault())
    }
}

@Composable
internal fun languageLabel(locale: Locale?): String {
    return locale?.let { localeDisplayName(it) }
        ?: stringResource(R.string.translator_page_auto_detect)
}
