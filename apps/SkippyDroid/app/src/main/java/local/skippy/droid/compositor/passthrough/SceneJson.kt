package local.skippy.droid.compositor.passthrough

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON ↔ [SceneNode] serialization, per PROTOCOL §5.
 *
 * Parsing is strict:
 *   - Unknown node types throw [SceneParseException] (`unknown_node_type`).
 *   - Palette props that aren't palette-enum names throw (`off_palette_hex`).
 *   - Unknown props throw (`unknown_prop`) — we surface the field name.
 *
 * The host catches these and emits a matching `host:error` on the view's
 * SSE, per PROTOCOL §13.
 */
object SceneJson {

    class SceneParseException(
        val errorType: String,
        message: String,
        val nodeId: String? = null,
        val propName: String? = null,
    ) : RuntimeException(message)

    // ── Parse ────────────────────────────────────────────────────────────────

    fun parseNode(json: JSONObject): SceneNode {
        val id   = json.optString("id").ifEmpty { err("schema_mismatch", "node missing id") }
        val type = json.optString("type").ifEmpty { err("schema_mismatch", "node $id missing type") }
        val props = json.optJSONObject("props") ?: JSONObject()
        val children = json.optJSONArray("children")
            ?.let { arr -> List(arr.length()) { parseNode(arr.getJSONObject(it)) } }
            ?: emptyList()

        return when (type) {
            "Box" -> SceneNode.Box(
                id = id,
                width   = parseSize(props, "width", SizeSpec.Wrap),
                height  = parseSize(props, "height", SizeSpec.Wrap),
                padding = props.optInt("padding", 0),
                align   = parseEnum(props, "align", Align.Start, id) { s ->
                    when (s) { "start" -> Align.Start; "center" -> Align.Center; "end" -> Align.End; else -> null }
                },
                children = children,
            )
            "Column" -> SceneNode.Column(
                id = id,
                width   = parseSize(props, "width", SizeSpec.Wrap),
                height  = parseSize(props, "height", SizeSpec.Wrap),
                padding = props.optInt("padding", 0),
                gap     = props.optInt("gap", 0),
                mainAxis  = parseMainAxis (props.optString("main_axis",  "start"), id),
                crossAxis = parseCrossAxis(props.optString("cross_axis", "start"), id),
                children  = children,
            )
            "Row" -> SceneNode.Row(
                id = id,
                width   = parseSize(props, "width", SizeSpec.Wrap),
                height  = parseSize(props, "height", SizeSpec.Wrap),
                padding = props.optInt("padding", 0),
                gap     = props.optInt("gap", 0),
                mainAxis  = parseMainAxis (props.optString("main_axis",  "start"), id),
                crossAxis = parseCrossAxis(props.optString("cross_axis", "start"), id),
                children  = children,
            )
            "Spacer" -> SceneNode.Spacer(
                id     = id,
                weight = props.optDouble("weight", 0.0).toFloat(),
                width  = parseSize(props, "width",  SizeSpec.Wrap),
                height = parseSize(props, "height", SizeSpec.Wrap),
            )
            "Text" -> SceneNode.Text(
                id        = id,
                text      = props.optString("text", ""),
                color     = requirePalette(props, "color", id),
                sizePx    = props.optInt("size_px", 0).ifZero {
                    err("schema_mismatch", "Text $id missing size_px", id, "size_px")
                },
                weight    = parseTextWeight(props.optString("weight", "normal"), id),
                align     = parseTextAlign (props.optString("align",  "start"),  id),
                monospace = props.optBoolean("monospace", true),
                sizeJustify = props.optBoolean("size_justify", false),
            )
            "Canvas" -> SceneNode.Canvas(
                id     = id,
                width  = props.optInt("width",  0),
                height = props.optInt("height", 0),
                ops    = parseCanvasOps(props.optJSONArray("ops"), id),
            )
            "FrameStream" -> SceneNode.FrameStream(
                id         = id,
                width      = parseSize(props, "width",  SizeSpec.Fill),
                height     = parseSize(props, "height", SizeSpec.Fill),
                url        = props.optString("url").ifEmpty {
                    err("schema_mismatch", "FrameStream $id missing url", id, "url")
                },
                fit        = parseFrameFit(props.optString("fit", "cover"), id),
                pose       = props.optBoolean("pose", false),
                background = props.optString("background").let {
                    if (it.isEmpty()) PaletteEnum.Black
                    else PaletteEnum.fromWire(it)
                        ?: err("off_palette_hex", "FrameStream $id.background='$it' not in palette", id, "background")
                },
            )
            "FocusTarget" -> {
                val childJson = json.optJSONObject("child")
                    ?: err("schema_mismatch", "FocusTarget $id missing child", id, "child")
                SceneNode.FocusTarget(
                    id       = id,
                    focusId  = props.optString("focus_id").ifEmpty {
                        err("schema_mismatch", "FocusTarget $id missing focus_id", id, "focus_id")
                    },
                    intent   = props.optString("intent").ifEmpty {
                        err("schema_mismatch", "FocusTarget $id missing intent", id, "intent")
                    },
                    child = parseNode(childJson),
                )
            }
            "Button" -> SceneNode.Button(
                id           = id,
                focusId      = props.optString("focus_id").ifEmpty {
                    err("schema_mismatch", "Button $id missing focus_id", id, "focus_id")
                },
                label        = props.optString("label", ""),
                intent       = props.optString("intent").ifEmpty {
                    err("schema_mismatch", "Button $id missing intent", id, "intent")
                },
                visibleLabel = props.optBoolean("visible_label", true),
                color        = props.optString("color").let {
                    if (it.isEmpty()) PaletteEnum.DimGreen
                    else PaletteEnum.fromWire(it)
                        ?: err("off_palette_hex", "Button $id.color='$it' not in palette", id, "color")
                },
            )
            "VerticalFillBar" -> SceneNode.VerticalFillBar(
                id       = id,
                fraction = props.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f),
                color    = requirePalette(props, "color", id),
            )
            "SignalStack" -> SceneNode.SignalStack(
                id      = id,
                bars    = props.optInt("bars", 0),
                maxBars = props.optInt("max_bars", 4),
                color   = requirePalette(props, "color", id),
            )
            "RmsGlyph" -> SceneNode.RmsGlyph(
                id    = id,
                rms   = props.optDouble("rms", 0.0).toFloat().coerceIn(0f, 1f),
                color = requirePalette(props, "color", id),
            )
            "ModeSegments" -> SceneNode.ModeSegments(
                id          = id,
                activeIndex = props.optInt("active_index", 0),
                total       = props.optInt("total", 1),
                color       = requirePalette(props, "color", id),
            )
            "HeadingTick" -> SceneNode.HeadingTick(
                id         = id,
                headingDeg = props.optDouble("heading_deg", 0.0),
                color      = requirePalette(props, "color", id),
            )
            else -> err("unknown_node_type", "node type '$type' not in v1 whitelist", id)
        }
    }

    // ── Serialize (used by cache restore + tests + mock view) ────────────────

    fun toJson(node: SceneNode): JSONObject {
        val obj = JSONObject()
        obj.put("id", node.id)
        val (type, props) = when (node) {
            is SceneNode.Box -> "Box" to JSONObject().apply {
                putSize("width", node.width); putSize("height", node.height)
                put("padding", node.padding); put("align", node.align.name.lowercase())
            }
            is SceneNode.Column -> "Column" to JSONObject().apply {
                putSize("width", node.width); putSize("height", node.height)
                put("padding", node.padding); put("gap", node.gap)
                put("main_axis",  node.mainAxis.wireName())
                put("cross_axis", node.crossAxis.wireName())
            }
            is SceneNode.Row -> "Row" to JSONObject().apply {
                putSize("width", node.width); putSize("height", node.height)
                put("padding", node.padding); put("gap", node.gap)
                put("main_axis",  node.mainAxis.wireName())
                put("cross_axis", node.crossAxis.wireName())
            }
            is SceneNode.Spacer -> "Spacer" to JSONObject().apply {
                put("weight", node.weight.toDouble())
                putSize("width", node.width); putSize("height", node.height)
            }
            is SceneNode.Text -> "Text" to JSONObject().apply {
                put("text", node.text); put("color", node.color.wireName)
                put("size_px", node.sizePx)
                put("weight", node.weight.name.lowercase())
                put("align",  node.align.name.lowercase())
                put("monospace", node.monospace)
                put("size_justify", node.sizeJustify)
            }
            is SceneNode.Canvas -> "Canvas" to JSONObject().apply {
                put("width", node.width); put("height", node.height)
                put("ops", JSONArray().also { arr -> node.ops.forEach { arr.put(opJson(it)) } })
            }
            is SceneNode.FrameStream -> "FrameStream" to JSONObject().apply {
                putSize("width", node.width); putSize("height", node.height)
                put("url", node.url); put("fit", node.fit.name.lowercase())
                put("pose", node.pose); put("background", node.background.wireName)
            }
            is SceneNode.FocusTarget -> "FocusTarget" to JSONObject().apply {
                put("focus_id", node.focusId); put("intent", node.intent)
            }
            is SceneNode.Button -> "Button" to JSONObject().apply {
                put("focus_id", node.focusId); put("label", node.label)
                put("intent", node.intent); put("visible_label", node.visibleLabel)
                put("color", node.color.wireName)
            }
            is SceneNode.VerticalFillBar -> "VerticalFillBar" to JSONObject().apply {
                put("fraction", node.fraction.toDouble()); put("color", node.color.wireName)
            }
            is SceneNode.SignalStack -> "SignalStack" to JSONObject().apply {
                put("bars", node.bars); put("max_bars", node.maxBars); put("color", node.color.wireName)
            }
            is SceneNode.RmsGlyph -> "RmsGlyph" to JSONObject().apply {
                put("rms", node.rms.toDouble()); put("color", node.color.wireName)
            }
            is SceneNode.ModeSegments -> "ModeSegments" to JSONObject().apply {
                put("active_index", node.activeIndex); put("total", node.total); put("color", node.color.wireName)
            }
            is SceneNode.HeadingTick -> "HeadingTick" to JSONObject().apply {
                put("heading_deg", node.headingDeg); put("color", node.color.wireName)
            }
        }
        obj.put("type", type)
        obj.put("props", props)
        val kids = childrenOf(node)
        if (kids.isNotEmpty()) {
            obj.put("children", JSONArray().also { arr -> kids.forEach { arr.put(toJson(it)) } })
        }
        if (node is SceneNode.FocusTarget) {
            obj.put("child", toJson(node.child))
        }
        return obj
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Flat list of children (empty for leaf nodes); used by parsing & patching. */
    fun childrenOf(node: SceneNode): List<SceneNode> = when (node) {
        is SceneNode.Box    -> node.children
        is SceneNode.Column -> node.children
        is SceneNode.Row    -> node.children
        else                -> emptyList()
    }

    private fun parseSize(props: JSONObject, key: String, default: SizeSpec): SizeSpec {
        if (!props.has(key)) return default
        val v = props.get(key)
        return when (v) {
            is Number -> SizeSpec.Px(v.toInt())
            is String -> when (v) {
                "fill" -> SizeSpec.Fill
                "wrap" -> SizeSpec.Wrap
                else   -> err("schema_mismatch", "size '$v' not in {fill,wrap,<number>}")
            }
            else -> err("schema_mismatch", "size prop '$key' must be number or 'fill'/'wrap'")
        }
    }

    private fun requirePalette(props: JSONObject, key: String, nodeId: String): PaletteEnum {
        val s = props.optString(key)
        if (s.isEmpty()) err("schema_mismatch", "$nodeId missing $key", nodeId, key)
        return PaletteEnum.fromWire(s)
            ?: err("off_palette_hex", "$nodeId.$key='$s' not in palette enum", nodeId, key)
    }

    private fun <E> parseEnum(
        props: JSONObject, key: String, default: E, id: String, mapper: (String) -> E?,
    ): E {
        val s = props.optString(key)
        if (s.isEmpty()) return default
        return mapper(s) ?: err("schema_mismatch", "$id.$key='$s' not a valid enum value", id, key)
    }

    private fun parseMainAxis(s: String, id: String): MainAxis = when (s) {
        "start"         -> MainAxis.Start
        "center"        -> MainAxis.Center
        "end"           -> MainAxis.End
        "space_between" -> MainAxis.SpaceBetween
        "space_around"  -> MainAxis.SpaceAround
        else            -> err("schema_mismatch", "$id.main_axis='$s' invalid", id, "main_axis")
    }

    private fun parseCrossAxis(s: String, id: String): CrossAxis = when (s) {
        "start"   -> CrossAxis.Start
        "center"  -> CrossAxis.Center
        "end"     -> CrossAxis.End
        "stretch" -> CrossAxis.Stretch
        else      -> err("schema_mismatch", "$id.cross_axis='$s' invalid", id, "cross_axis")
    }

    private fun parseTextAlign(s: String, id: String): TextAlign = when (s) {
        "start"  -> TextAlign.Start
        "center" -> TextAlign.Center
        "end"    -> TextAlign.End
        else     -> err("schema_mismatch", "$id.align='$s' invalid", id, "align")
    }

    private fun parseTextWeight(s: String, id: String): TextWeight = when (s) {
        "normal" -> TextWeight.Normal
        "bold"   -> TextWeight.Bold
        else     -> err("schema_mismatch", "$id.weight='$s' invalid", id, "weight")
    }

    private fun parseFrameFit(s: String, id: String): FrameFit = when (s) {
        "cover"   -> FrameFit.Cover
        "contain" -> FrameFit.Contain
        "fill"    -> FrameFit.Fill
        else      -> err("schema_mismatch", "$id.fit='$s' invalid", id, "fit")
    }

    private fun parseCanvasOps(arr: JSONArray?, canvasId: String): List<CanvasOp> {
        if (arr == null) return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val op = o.optString("op")
            val color = PaletteEnum.fromWire(o.optString("color"))
                ?: err("off_palette_hex", "Canvas $canvasId op[$i].color not in palette", canvasId, "color")
            when (op) {
                "rect" -> CanvasOp.Rect(
                    o.optDouble("x").toFloat(), o.optDouble("y").toFloat(),
                    o.optDouble("w").toFloat(), o.optDouble("h").toFloat(),
                    color,
                    o.optBoolean("stroke", false),
                    o.optDouble("stroke_px", 2.0).toFloat(),
                )
                "circle" -> CanvasOp.Circle(
                    o.optDouble("cx").toFloat(), o.optDouble("cy").toFloat(),
                    o.optDouble("r").toFloat(), color,
                    o.optBoolean("stroke", false),
                    o.optDouble("stroke_px", 2.0).toFloat(),
                )
                "line" -> CanvasOp.Line(
                    o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(),
                    o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat(),
                    color, o.optDouble("stroke_px", 2.0).toFloat(),
                )
                "path" -> CanvasOp.Path(
                    o.optString("d"), color,
                    o.optBoolean("stroke", false),
                    o.optDouble("stroke_px", 2.0).toFloat(),
                )
                else -> err("schema_mismatch", "Canvas $canvasId op[$i] has unknown op '$op'", canvasId)
            }
        }
    }

    private fun opJson(op: CanvasOp): JSONObject = JSONObject().apply {
        when (op) {
            is CanvasOp.Rect -> {
                put("op", "rect")
                put("x", op.x.toDouble()); put("y", op.y.toDouble())
                put("w", op.w.toDouble()); put("h", op.h.toDouble())
                put("color", op.color.wireName)
                put("stroke", op.stroke); put("stroke_px", op.strokePx.toDouble())
            }
            is CanvasOp.Circle -> {
                put("op", "circle")
                put("cx", op.cx.toDouble()); put("cy", op.cy.toDouble())
                put("r", op.r.toDouble()); put("color", op.color.wireName)
                put("stroke", op.stroke); put("stroke_px", op.strokePx.toDouble())
            }
            is CanvasOp.Line -> {
                put("op", "line")
                put("x1", op.x1.toDouble()); put("y1", op.y1.toDouble())
                put("x2", op.x2.toDouble()); put("y2", op.y2.toDouble())
                put("color", op.color.wireName)
                put("stroke_px", op.strokePx.toDouble())
            }
            is CanvasOp.Path -> {
                put("op", "path"); put("d", op.d); put("color", op.color.wireName)
                put("stroke", op.stroke); put("stroke_px", op.strokePx.toDouble())
            }
        }
    }

    private fun JSONObject.putSize(key: String, v: SizeSpec) {
        when (v) {
            is SizeSpec.Fill  -> put(key, "fill")
            is SizeSpec.Wrap  -> put(key, "wrap")
            is SizeSpec.Px    -> put(key, v.value)
        }
    }

    private fun MainAxis.wireName(): String = when (this) {
        MainAxis.Start        -> "start"
        MainAxis.Center       -> "center"
        MainAxis.End          -> "end"
        MainAxis.SpaceBetween -> "space_between"
        MainAxis.SpaceAround  -> "space_around"
    }

    private fun CrossAxis.wireName(): String = when (this) {
        CrossAxis.Start   -> "start"
        CrossAxis.Center  -> "center"
        CrossAxis.End     -> "end"
        CrossAxis.Stretch -> "stretch"
    }

    private fun Int.ifZero(handler: () -> Int): Int = if (this == 0) handler() else this

    private fun err(
        errorType: String, message: String,
        nodeId: String? = null, propName: String? = null,
    ): Nothing = throw SceneParseException(errorType, message, nodeId, propName)
}
