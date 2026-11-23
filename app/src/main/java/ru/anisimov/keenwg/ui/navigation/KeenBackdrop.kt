package ru.anisimov.keenwg.ui.navigation

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import ru.anisimov.keenwg.ui.theme.KeenGlass
import kotlin.math.ceil

/** GPU display-list capture only: never exports or retains a screen bitmap. */
internal class KeenBackdrop(val layer: GraphicsLayer) {
    var origin = Offset.Zero
    val effects = mutableSetOf<BackdropEffectNode>()
}

internal fun Modifier.keenBackdropSource(state: KeenBackdrop): Modifier = then(SourceElement(state))

private data class SourceElement(val state: KeenBackdrop) : ModifierNodeElement<SourceNode>() {
    override fun create() = SourceNode(state)
    override fun update(node: SourceNode) { node.state = state }
    override fun InspectorInfo.inspectableProperties() { name = "keenBackdropSource" }
}

private class SourceNode(var state: KeenBackdrop) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) { state.origin = coordinates.positionInRoot() }
    override fun ContentDrawScope.draw() {
        val source = this
        state.layer.record { source.drawContent() }
        drawLayer(state.layer)
        // Redraw the small glass region when scrolling changes the source display list.
        state.effects.forEach { if (it.isAttached) it.invalidateDraw() }
    }
}

internal fun Modifier.keenBackdropEffect(state: KeenBackdrop, layer: GraphicsLayer, tint: Color): Modifier =
    then(EffectElement(state, layer, tint))

private data class EffectElement(val state: KeenBackdrop, val layer: GraphicsLayer, val tint: Color) : ModifierNodeElement<BackdropEffectNode>() {
    override fun create() = BackdropEffectNode(state, layer, tint)
    override fun update(node: BackdropEffectNode) {
        node.state.effects.remove(node)
        node.state = state
        node.layer = layer
        node.tint = tint
        state.effects.add(node)
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "keenBackdropEffect" }
}

internal class BackdropEffectNode(var state: KeenBackdrop, var layer: GraphicsLayer, var tint: Color) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var origin = Offset.Zero
    override fun onAttach() { state.effects.add(this) }
    override fun onDetach() { state.effects.remove(this) }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        origin = coordinates.positionInRoot()
        invalidateDraw()
    }
    override fun ContentDrawScope.draw() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && state.layer.size.width > 0) {
            val radius = KeenGlass.blurRadius.toPx()
            val margin = ceil(radius * 2).toInt()
            val relative = origin - state.origin
            // Capture only this region plus the blur kernel, not a full-screen offscreen texture.
            layer.record(size = IntSize(size.width.toInt() + margin * 2, size.height.toInt() + margin * 2)) {
                translate(margin - relative.x, margin - relative.y) { drawLayer(state.layer) }
            }
            layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(layer) }
            drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = KeenGlass.tintTop), tint.copy(alpha = KeenGlass.tintBottom))))
        } else {
            // Android 8–11 has no RenderEffect; retain contrast instead of pretending to blur.
            drawRect(tint.copy(alpha = KeenGlass.fallbackTint))
        }
        drawContent() // Icons and labels are never blurred.
    }
}
