package com.skippy.droid.compositor.passthrough

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as GfxPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text as ComposeText
import com.skippy.droid.compositor.HeadingTick as SkippyHeadingTick
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.compositor.ModeSegments as SkippyModeSegments
import com.skippy.droid.compositor.RmsGlyph as SkippyRmsGlyph
import com.skippy.droid.compositor.SignalStack as SkippySignalStack
import com.skippy.droid.compositor.SizeJustifiedText
import com.skippy.droid.compositor.VerticalFillBar as SkippyVerticalFillBar

/**
 * @Composable renderer for the passthrough scene tree (PROTOCOL §5).
 *
 * Render order is depth-first, declaration order — Compose's natural Box/
 * Column/Row stacking gives "later siblings paint on top" for free
 * (PROTOCOL §5.1a). That means a view places `FrameStream` early in its
 * tree for a video background, or late for frames overlaid on top of UI.
 *
 * Dependencies:
 *   - [frameSource] composes live MJPEG frames for [SceneNode.FrameStream]
 *     nodes. Inject a real [FrameStreamConsumer] in production; a stub
 *     (black rectangle + "frames" label) is fine for preview.
 *   - [onFocusIntent] fires when a focus target is addressed — reserved
 *     for future on-screen focus indication; v1 voice flow routes through
 *     the host SSE, not this callback.
 *
 * Reuses Skippy's existing symbology primitives from `compositor/Symbology.kt`
 * so a view's `VerticalFillBar` node renders pixel-identical to Skippy's own
 * sidebar bar.
 */
class SceneRenderer(
    private val frameSource: FrameSource,
) {
    /** Factory for [SceneNode.FrameStream] rendering — pluggable so the JVM preview can stub it. */
    fun interface FrameSource {
        @Composable
        fun Render(url: String, fit: FrameFit, background: Color, modifier: Modifier)
    }

    /** Top-level entry. Compose the whole scene. */
    @Composable
    fun Render(scene: Scene, modifier: Modifier = Modifier) {
        Box(modifier = modifier) {
            render(scene.root, Modifier)
        }
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    @Composable
    private fun render(node: SceneNode, mod: Modifier) {
        when (node) {
            is SceneNode.Box             -> renderBox(node, mod)
            is SceneNode.Column          -> renderColumn(node, mod)
            is SceneNode.Row             -> renderRow(node, mod)
            is SceneNode.Spacer          -> renderSpacer(node, mod)
            is SceneNode.Text            -> renderText(node, mod)
            is SceneNode.Canvas          -> renderCanvas(node, mod)
            is SceneNode.FrameStream     -> renderFrameStream(node, mod)
            is SceneNode.FocusTarget     -> render(node.child, mod)  // focus is voice-only in v1
            is SceneNode.Button          -> renderButton(node, mod)
            is SceneNode.VerticalFillBar -> SkippyVerticalFillBar(
                fraction = node.fraction, color = node.color.toColor(), modifier = mod,
            )
            is SceneNode.SignalStack     -> SkippySignalStack(
                bars = node.bars, maxBars = node.maxBars, color = node.color.toColor(), modifier = mod,
            )
            is SceneNode.RmsGlyph        -> SkippyRmsGlyph(
                rms = node.rms, color = node.color.toColor(), modifier = mod,
            )
            is SceneNode.ModeSegments    -> SkippyModeSegments(
                activeIndex = node.activeIndex, total = node.total,
                color = node.color.toColor(), modifier = mod,
            )
            is SceneNode.HeadingTick     -> SkippyHeadingTick(
                headingDeg = node.headingDeg.toFloat(), color = node.color.toColor(), modifier = mod,
            )
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    @Composable
    private fun renderBox(n: SceneNode.Box, mod: Modifier) {
        Box(
            modifier = mod
                .applySize(n.width, n.height)
                .padding(n.padding.dp),
            contentAlignment = n.align.toContentAlignment(),
        ) {
            n.children.forEach { render(it, Modifier) }
        }
    }

    @Composable
    private fun renderColumn(n: SceneNode.Column, mod: Modifier) {
        Column(
            modifier = mod
                .applySize(n.width, n.height)
                .padding(n.padding.dp),
            verticalArrangement   = n.mainAxis.toVerticalArrangement(n.gap),
            horizontalAlignment   = n.crossAxis.toHorizontalAlignment(),
        ) {
            n.children.forEach { render(it, Modifier) }
        }
    }

    @Composable
    private fun renderRow(n: SceneNode.Row, mod: Modifier) {
        Row(
            modifier = mod
                .applySize(n.width, n.height)
                .padding(n.padding.dp),
            horizontalArrangement = n.mainAxis.toHorizontalArrangement(n.gap),
            verticalAlignment     = n.crossAxis.toVerticalAlignment(),
        ) {
            n.children.forEach { render(it, Modifier) }
        }
    }

    @Composable
    private fun renderSpacer(n: SceneNode.Spacer, mod: Modifier) {
        // Weight-based flex is left for future; Phase 1 treats Spacer as a fixed gap.
        Spacer(modifier = mod.applySize(n.width, n.height))
    }

    // ── Content ──────────────────────────────────────────────────────────────

    @Composable
    private fun renderText(n: SceneNode.Text, mod: Modifier) {
        if (n.sizeJustify) {
            // Size-justify to the slot the view placed us in.
            SizeJustifiedText(
                text = n.text,
                color = n.color.toColor(),
                fontFamily = if (n.monospace) FontFamily.Monospace else FontFamily.Default,
                fontWeight = n.weight.toFontWeight(),
                textAlign = n.align.toComposeTextAlign(),
                modifier = mod.fillMaxSize(),
            )
        } else {
            ComposeText(
                text = n.text,
                color = n.color.toColor(),
                fontSize = n.sizePx.sp,
                fontFamily = if (n.monospace) FontFamily.Monospace else FontFamily.Default,
                fontWeight = n.weight.toFontWeight(),
                textAlign = n.align.toComposeTextAlign(),
                modifier = mod,
            )
        }
    }

    @Composable
    private fun renderCanvas(n: SceneNode.Canvas, mod: Modifier) {
        Canvas(modifier = mod.size(n.width.dp, n.height.dp)) {
            n.ops.forEach { op ->
                when (op) {
                    is CanvasOp.Rect -> {
                        if (op.stroke) {
                            drawRect(
                                color = op.color.toColor(),
                                topLeft = Offset(op.x, op.y),
                                size = Size(op.w, op.h),
                                style = Stroke(width = op.strokePx),
                            )
                        } else {
                            drawRect(
                                color = op.color.toColor(),
                                topLeft = Offset(op.x, op.y),
                                size = Size(op.w, op.h),
                            )
                        }
                    }
                    is CanvasOp.Circle -> {
                        if (op.stroke) {
                            drawCircle(
                                color = op.color.toColor(),
                                radius = op.r,
                                center = Offset(op.cx, op.cy),
                                style = Stroke(width = op.strokePx),
                            )
                        } else {
                            drawCircle(
                                color = op.color.toColor(),
                                radius = op.r,
                                center = Offset(op.cx, op.cy),
                            )
                        }
                    }
                    is CanvasOp.Line -> {
                        drawLine(
                            color = op.color.toColor(),
                            start = Offset(op.x1, op.y1),
                            end   = Offset(op.x2, op.y2),
                            strokeWidth = op.strokePx,
                        )
                    }
                    is CanvasOp.Path -> {
                        val path = parseSvgPath(op.d)
                        if (op.stroke) {
                            drawPath(path = path, color = op.color.toColor(),
                                style = Stroke(width = op.strokePx))
                        } else {
                            drawPath(path = path, color = op.color.toColor())
                        }
                    }
                }
            }
        }
    }

    // ── Media ────────────────────────────────────────────────────────────────

    @Composable
    private fun renderFrameStream(n: SceneNode.FrameStream, mod: Modifier) {
        frameSource.Render(
            url = n.url,
            fit = n.fit,
            background = n.background.toColor(),
            modifier = mod.applySize(n.width, n.height),
        )
    }

    // ── Interaction ──────────────────────────────────────────────────────────

    @Composable
    private fun renderButton(n: SceneNode.Button, mod: Modifier) {
        // v1 doctrine: Captain has no touch. Button paints a bordered box and
        // (optionally) its label. Activation is voice-only via the focus_id.
        val border = n.color.toColor()
        Box(
            modifier = mod
                .border(width = 2.dp, color = border)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (n.visibleLabel && n.label.isNotEmpty()) {
                ComposeText(
                    text = n.label,
                    color = HudPalette.White,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Minimal SVG path subset: M, L, Q, C, Z. */
    private fun parseSvgPath(d: String): GfxPath {
        val path = GfxPath()
        val tokens = d.replace(",", " ").trim().split(Regex("\\s+"))
        var i = 0
        var cx = 0f
        var cy = 0f
        while (i < tokens.size) {
            val cmd = tokens[i]
            i++
            when (cmd) {
                "M" -> { val x = tokens[i++].toFloat(); val y = tokens[i++].toFloat()
                         path.moveTo(x, y); cx = x; cy = y }
                "L" -> { val x = tokens[i++].toFloat(); val y = tokens[i++].toFloat()
                         path.lineTo(x, y); cx = x; cy = y }
                "Q" -> { val x1 = tokens[i++].toFloat(); val y1 = tokens[i++].toFloat()
                         val x = tokens[i++].toFloat(); val y = tokens[i++].toFloat()
                         path.quadraticBezierTo(x1, y1, x, y); cx = x; cy = y }
                "C" -> { val x1 = tokens[i++].toFloat(); val y1 = tokens[i++].toFloat()
                         val x2 = tokens[i++].toFloat(); val y2 = tokens[i++].toFloat()
                         val x  = tokens[i++].toFloat(); val y  = tokens[i++].toFloat()
                         path.cubicTo(x1, y1, x2, y2, x, y); cx = x; cy = y }
                "Z", "z" -> path.close()
                else -> { /* ignore unknown commands in v1 — keep renderer tolerant */ }
            }
        }
        return path
    }
}

// ── Enum → Compose mappings (top-level so the renderer stays compact) ────────

internal fun PaletteEnum.toColor(): Color = when (this) {
    PaletteEnum.Black      -> HudPalette.Black
    PaletteEnum.Green      -> HudPalette.Green
    PaletteEnum.White      -> HudPalette.White
    PaletteEnum.Amber      -> HudPalette.Amber
    PaletteEnum.Cyan       -> HudPalette.Cyan
    PaletteEnum.Violet     -> HudPalette.Violet
    PaletteEnum.Red        -> HudPalette.Red
    PaletteEnum.DimGreen   -> HudPalette.DimGreen
    PaletteEnum.DimGreenHi -> HudPalette.DimGreenHi
}

internal fun Modifier.applySize(w: SizeSpec, h: SizeSpec): Modifier {
    var m = this
    m = when (w) {
        SizeSpec.Fill   -> m.fillMaxWidth()
        SizeSpec.Wrap   -> m
        is SizeSpec.Px  -> m.width(w.value.dp)
    }
    m = when (h) {
        SizeSpec.Fill   -> m.fillMaxHeight()
        SizeSpec.Wrap   -> m
        is SizeSpec.Px  -> m.height(h.value.dp)
    }
    return m
}

internal fun Align.toContentAlignment(): Alignment = when (this) {
    Align.Start  -> Alignment.TopStart
    Align.Center -> Alignment.Center
    Align.End    -> Alignment.BottomEnd
}

internal fun MainAxis.toVerticalArrangement(gap: Int): Arrangement.Vertical = when (this) {
    MainAxis.Start        -> if (gap > 0) Arrangement.spacedBy(gap.dp) else Arrangement.Top
    MainAxis.Center       -> if (gap > 0) Arrangement.spacedBy(gap.dp, Alignment.CenterVertically)
                              else Arrangement.Center
    MainAxis.End          -> if (gap > 0) Arrangement.spacedBy(gap.dp, Alignment.Bottom)
                              else Arrangement.Bottom
    MainAxis.SpaceBetween -> Arrangement.SpaceBetween
    MainAxis.SpaceAround  -> Arrangement.SpaceAround
}

internal fun MainAxis.toHorizontalArrangement(gap: Int): Arrangement.Horizontal = when (this) {
    MainAxis.Start        -> if (gap > 0) Arrangement.spacedBy(gap.dp) else Arrangement.Start
    MainAxis.Center       -> if (gap > 0) Arrangement.spacedBy(gap.dp, Alignment.CenterHorizontally)
                              else Arrangement.Center
    MainAxis.End          -> if (gap > 0) Arrangement.spacedBy(gap.dp, Alignment.End)
                              else Arrangement.End
    MainAxis.SpaceBetween -> Arrangement.SpaceBetween
    MainAxis.SpaceAround  -> Arrangement.SpaceAround
}

internal fun CrossAxis.toHorizontalAlignment(): Alignment.Horizontal = when (this) {
    CrossAxis.Start   -> Alignment.Start
    CrossAxis.Center  -> Alignment.CenterHorizontally
    CrossAxis.End     -> Alignment.End
    CrossAxis.Stretch -> Alignment.CenterHorizontally  // Compose Column has no Stretch; approximate
}

internal fun CrossAxis.toVerticalAlignment(): Alignment.Vertical = when (this) {
    CrossAxis.Start   -> Alignment.Top
    CrossAxis.Center  -> Alignment.CenterVertically
    CrossAxis.End     -> Alignment.Bottom
    CrossAxis.Stretch -> Alignment.CenterVertically
}

internal fun TextAlign.toComposeTextAlign(): ComposeTextAlign = when (this) {
    TextAlign.Start  -> ComposeTextAlign.Start
    TextAlign.Center -> ComposeTextAlign.Center
    TextAlign.End    -> ComposeTextAlign.End
}

internal fun TextWeight.toFontWeight(): FontWeight = when (this) {
    TextWeight.Normal -> FontWeight.Normal
    TextWeight.Bold   -> FontWeight.Bold
}
