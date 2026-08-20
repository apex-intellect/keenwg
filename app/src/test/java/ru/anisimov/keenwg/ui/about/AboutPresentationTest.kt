package ru.anisimov.keenwg.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutPresentationTest {
    @Test
    fun `about keeps official project and company links in a stable order`() {
        assertEquals(
            listOf(
                AboutLinkTarget.OFFICIAL_SOURCE,
                AboutLinkTarget.STAR_REPOSITORY,
                AboutLinkTarget.COMPANY_WEBSITE,
                AboutLinkTarget.DEVELOPER,
                AboutLinkTarget.BRAND_POLICY,
            ),
            aboutLinkTargets(),
        )
    }
}
