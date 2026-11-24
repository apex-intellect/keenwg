package ru.anisimov.keenwg.ui.navigation

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asComposeRenderEffect

/** A static optical edge: only background pixels move, never labels or hit targets. */
@RequiresApi(33)
internal class KeenGlassLens {
    private val shader = RuntimeShader(SOURCE)
    private var key: List<Float>? = null
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun effect(width: Float, height: Float, inset: Float, corner: Float, band: Float, bend: Float, blur: Float): androidx.compose.ui.graphics.RenderEffect {
        val next = listOf(width, height, inset, corner, band, bend, blur)
        if (key == next) return requireNotNull(cached)
        shader.setFloatUniform("extent", width, height)
        shader.setFloatUniform("inset", inset)
        shader.setFloatUniform("corner", minOf(corner, width / 2, height / 2))
        shader.setFloatUniform("band", band)
        shader.setFloatUniform("bend", bend)
        val refract = RenderEffect.createRuntimeShaderEffect(shader, "content")
        return RenderEffect.createChainEffect(refract, RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
            .asComposeRenderEffect().also { key = next; cached = it }
    }

    private companion object {
        const val SOURCE = """
            uniform shader content;
            uniform float2 extent;
            uniform float inset;
            uniform float corner;
            uniform float band;
            uniform float bend;
            half4 main(float2 coord) {
                float2 p = coord - float2(inset) - extent * 0.5;
                float2 q = abs(p) - (extent * 0.5 - float2(corner));
                float distance = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - corner;
                float edge = 1.0 - smoothstep(0.0, band, max(-distance, 0.0));
                float2 direction = sign(p) * max(q, float2(0.001));
                float2 normal = direction / max(length(direction), 0.001);
                return content.eval(coord - normal * bend * edge * edge);
            }
        """
    }
}
