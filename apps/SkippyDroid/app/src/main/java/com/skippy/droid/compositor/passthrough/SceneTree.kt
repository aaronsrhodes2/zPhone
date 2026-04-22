package com.skippy.droid.compositor.passthrough

/**
 * Scene-tree data model for the Passthrough UI Transfer Protocol v1.
 *
 * Mirrors the wire format defined in `misc/PASSTHROUGH_PROTOCOL.md` §5.
 * Every mounted passthrough view posts a tree of these nodes; Skippy
 * renders them natively via Compose (see [SceneRenderer]).
 *
 * ── Invariants ─────────────────────────────────────────────────────────────
 *   - Node `id` is stable within a view's lifetime. Patch paths can address it.
 *   - Palette props accept [PaletteEnum] names only. Never hex; off-palette
 *     values are rejected at parse (→ `host:error off_palette_hex`).
 *   - `children` only on layout nodes (Box / Column / Row). Leaf nodes
 *     never carry children; the validator enforces this.
 *   - Render order is tree order: depth-first, later siblings paint on top.
 *     This is the `z-index` model (see PROTOCOL §5.1a).
 */
sealed class SceneNode {
    abstract val id: String

    /** Layout: absolute Box. Children stack at origin. */
    data class Box(
        override val id: String,
        val width:   SizeSpec = SizeSpec.Wrap,
        val height:  SizeSpec = SizeSpec.Wrap,
        val padding: Int = 0,
        val align:   Align = Align.Start,
        val children: List<SceneNode> = emptyList(),
    ) : SceneNode()

    /** Layout: vertical stack. */
    data class Column(
        override val id: String,
        val width:     SizeSpec = SizeSpec.Wrap,
        val height:    SizeSpec = SizeSpec.Wrap,
        val padding:   Int = 0,
        val gap:       Int = 0,
        val mainAxis:  MainAxis  = MainAxis.Start,
        val crossAxis: CrossAxis = CrossAxis.Start,
        val children:  List<SceneNode> = emptyList(),
    ) : SceneNode()

    /** Layout: horizontal stack. */
    data class Row(
        override val id: String,
        val width:     SizeSpec = SizeSpec.Wrap,
        val height:    SizeSpec = SizeSpec.Wrap,
        val padding:   Int = 0,
        val gap:       Int = 0,
        val mainAxis:  MainAxis  = MainAxis.Start,
        val crossAxis: CrossAxis = CrossAxis.Start,
        val children:  List<SceneNode> = emptyList(),
    ) : SceneNode()

    /** Layout: flexible spacer. `weight` > 0 takes remaining space. */
    data class Spacer(
        override val id: String,
        val weight: Float = 0f,
        val width:  SizeSpec = SizeSpec.Wrap,
        val height: SizeSpec = SizeSpec.Wrap,
    ) : SceneNode()

    /** Content: text. Palette-enum color required. */
    data class Text(
        override val id: String,
        val text:        String,
        val color:       PaletteEnum,
        val sizePx:      Int,
        val weight:      TextWeight = TextWeight.Normal,
        val align:       TextAlign  = TextAlign.Start,
        val monospace:   Boolean = true,
        val sizeJustify: Boolean = false,
    ) : SceneNode()

    /** Content: canvas with ordered primitive ops. */
    data class Canvas(
        override val id: String,
        val width:  Int,
        val height: Int,
        val ops:    List<CanvasOp> = emptyList(),
    ) : SceneNode()

    /**
     * Media: real-time AR frame rectangle.
     *
     * Frames arrive via MJPEG at [url] — see PROTOCOL §16.
     * Pose feedback is separate ([pose] subscribes the view to the pose SSE).
     */
    data class FrameStream(
        override val id: String,
        val width:      SizeSpec = SizeSpec.Fill,
        val height:     SizeSpec = SizeSpec.Fill,
        val url:        String,
        val fit:        FrameFit = FrameFit.Cover,
        val pose:       Boolean  = false,
        val background: PaletteEnum = PaletteEnum.Black,
    ) : SceneNode()

    /** Interaction: voice-addressable wrapper around a single child. */
    data class FocusTarget(
        override val id: String,
        val focusId: String,
        val intent:  String,
        val child:   SceneNode,
    ) : SceneNode()

    /** Interaction: voice button. */
    data class Button(
        override val id: String,
        val focusId:      String,
        val label:        String,
        val intent:       String,
        val visibleLabel: Boolean = true,
        val color:        PaletteEnum = PaletteEnum.DimGreen,
    ) : SceneNode()

    /** Symbology: vertical fill bar (0..1). Mirrors compositor/Symbology VerticalFillBar. */
    data class VerticalFillBar(
        override val id: String,
        val fraction: Float,
        val color:    PaletteEnum,
    ) : SceneNode()

    /** Symbology: stacked signal bars (0..maxBars). */
    data class SignalStack(
        override val id: String,
        val bars:    Int,
        val maxBars: Int = 4,
        val color:   PaletteEnum,
    ) : SceneNode()

    /** Symbology: mic RMS glyph (0..1). */
    data class RmsGlyph(
        override val id: String,
        val rms:   Float,
        val color: PaletteEnum,
    ) : SceneNode()

    /** Symbology: N horizontal segments, one lit. */
    data class ModeSegments(
        override val id: String,
        val activeIndex: Int,
        val total:       Int,
        val color:       PaletteEnum,
    ) : SceneNode()

    /** Symbology: heading tick. */
    data class HeadingTick(
        override val id: String,
        val headingDeg: Double,
        val color:      PaletteEnum,
    ) : SceneNode()
}

/** Size specification for layout nodes. */
sealed class SizeSpec {
    object Fill : SizeSpec()
    object Wrap : SizeSpec()
    data class Px(val value: Int) : SizeSpec()
}

enum class Align     { Start, Center, End }
enum class MainAxis  { Start, Center, End, SpaceBetween, SpaceAround }
enum class CrossAxis { Start, Center, End, Stretch }
enum class TextAlign { Start, Center, End }
enum class TextWeight { Normal, Bold }
enum class FrameFit  { Cover, Contain, Fill }

/**
 * Canonical palette enum names accepted on the wire.
 * See [PASSTHROUGH_PROTOCOL.md §5.4] and Skippy's native [HudPalette].
 */
enum class PaletteEnum(val wireName: String) {
    Black      ("black"),
    Green      ("green"),
    White      ("white"),
    Amber      ("amber"),
    Cyan       ("cyan"),
    Violet     ("violet"),
    Red        ("red"),
    DimGreen   ("dim_green"),
    DimGreenHi ("dim_green_hi");

    companion object {
        fun fromWire(s: String): PaletteEnum? = entries.firstOrNull { it.wireName == s }
    }
}

/** Canvas primitive operations — see PROTOCOL §5.3. */
sealed class CanvasOp {
    data class Rect(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val color: PaletteEnum, val stroke: Boolean = false, val strokePx: Float = 2f,
    ) : CanvasOp()

    data class Circle(
        val cx: Float, val cy: Float, val r: Float,
        val color: PaletteEnum, val stroke: Boolean = false, val strokePx: Float = 2f,
    ) : CanvasOp()

    data class Line(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val color: PaletteEnum, val strokePx: Float = 2f,
    ) : CanvasOp()

    data class Path(
        val d: String,
        val color: PaletteEnum, val stroke: Boolean = false, val strokePx: Float = 2f,
    ) : CanvasOp()
}

/**
 * A parsed scene — the root node plus the `seq` it arrived on.
 * The host stores the latest [Scene] per view for reactivation (PROTOCOL §11).
 */
data class Scene(val root: SceneNode, val seq: Long = 0L)

/**
 * Patch operation — see PROTOCOL §6.
 * [path] grammar: `/root/children/<i>/props/<name>` etc.
 */
sealed class PatchOp {
    abstract val path: String
    data class Set   (override val path: String, val value: Any?) : PatchOp()
    data class Insert(override val path: String, val value: Any?) : PatchOp()
    data class Remove(override val path: String)                  : PatchOp()
}
