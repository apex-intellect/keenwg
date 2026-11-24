package ru.anisimov.keenwg.ui

import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import ru.anisimov.keenwg.ui.navigation.KeenGlassLens

@SdkSuppress(minSdkVersion = 33)
class GlassLensTest {
    @Test fun refractionShaderCompilesAndReusesItsEffectUntilBoundsChange() {
        val lens = KeenGlassLens()
        val first = lens.effect(600f, 132f, 56f, 58f, 28f, 8f, 28f)
        assertSame(first, lens.effect(600f, 132f, 56f, 58f, 28f, 8f, 28f))
        assertNotSame(first, lens.effect(800f, 132f, 56f, 58f, 28f, 8f, 28f))
    }
}
