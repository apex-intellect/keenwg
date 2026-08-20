package ru.anisimov.keenwg.ui.about

internal enum class AboutLinkTarget(val url: String) {
    OFFICIAL_SOURCE("https://github.com/apex-intellect/keenwg"),
    STAR_REPOSITORY("https://github.com/apex-intellect/keenwg"),
    COMPANY_WEBSITE("https://apex-intellect.ru/"),
    DEVELOPER("https://github.com/th-notorious"),
    BRAND_POLICY("https://github.com/apex-intellect/keenwg/blob/main/TRADEMARKS.md"),
}

internal fun aboutLinkTargets(): List<AboutLinkTarget> = listOf(
    AboutLinkTarget.OFFICIAL_SOURCE,
    AboutLinkTarget.STAR_REPOSITORY,
    AboutLinkTarget.COMPANY_WEBSITE,
    AboutLinkTarget.DEVELOPER,
    AboutLinkTarget.BRAND_POLICY,
)
