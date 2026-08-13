package ru.anisimov.keenwg.ui.localization

/** Languages supported by the packaged Android resources. */
enum class AppLanguage(val languageTags: String) {
    SYSTEM(""),
    RUSSIAN("ru"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromLanguageTags(languageTags: String): AppLanguage {
            val primaryTag = languageTags
                .substringBefore(',')
                .trim()
                .substringBefore('-')
                .lowercase()
            return when (primaryTag) {
                RUSSIAN.languageTags -> RUSSIAN
                ENGLISH.languageTags -> ENGLISH
                else -> SYSTEM
            }
        }
    }
}
