package local.skippy.droid.compositor.passthrough

import org.json.JSONArray
import org.json.JSONObject

/**
 * Apply a list of [PatchOp]s to a [Scene] and return the new scene.
 *
 * Implementation strategy: serialize tree to JSON, mutate the JSON at the
 * given path, re-parse. This gives:
 *   - Atomic failure semantics (parse errors post-apply roll back to prior
 *     scene — see [apply]).
 *   - Free validation on every patch (any op that introduces an invalid
 *     palette enum, unknown node type, etc. fails in [SceneJson.parseNode]
 *     just like a fresh `scene:full` would).
 *   - Simplicity over performance. The 10-patches/sec cadence cap
 *     (PROTOCOL §7) keeps this well below any perf floor.
 *
 * Path grammar (PROTOCOL §6.2):
 *   `/root`                                 — the root node
 *   `/root/children/<i>`                    — ith child of root (zero-indexed)
 *   `/root/children/<i>/props/<name>`       — prop by name on a child
 *   `/root/children/<i>/children/<j>/…`     — arbitrary depth
 *
 * Reserved:
 *   - `insert` REQUIRES the last segment to be a numeric index preceded
 *     by a "children" segment (see PROTOCOL §6.2).
 *   - `remove` on an array index shifts later siblings left.
 *
 * Errors raise [PatchException] with `patch_path_invalid` / `schema_mismatch`
 * types to match PROTOCOL §13.2. The host maps these to `host:error`.
 */
object ScenePatchApplier {

    class PatchException(val errorType: String, message: String) : RuntimeException(message)

    /**
     * Apply [ops] in order. Atomic: if any op fails, the original scene is
     * returned unchanged and the exception propagates.
     *
     * @return new [Scene] with `seq` advanced by `ops.size`.
     */
    fun apply(scene: Scene, ops: List<PatchOp>): Scene {
        if (ops.isEmpty()) return scene
        val json = SceneJson.toJson(scene.root)
        // Mutations happen on this JSON tree; we re-parse at the end so
        // type safety + palette enforcement run on the result.
        ops.forEach { applyOp(json, it) }
        return try {
            Scene(
                root = SceneJson.parseNode(json),
                seq  = scene.seq + ops.size,
            )
        } catch (e: SceneJson.SceneParseException) {
            // Re-throw as patch failure so the host classifies it correctly.
            throw PatchException("schema_mismatch",
                "patch produced invalid scene: ${e.message}")
        }
    }

    // ── Path parsing ─────────────────────────────────────────────────────────

    private fun parsePath(path: String): List<String> {
        if (!path.startsWith("/")) fail("patch_path_invalid", "path must start with '/': $path")
        val segs = path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) fail("patch_path_invalid", "empty path")
        if (segs.first() != "root") fail("patch_path_invalid", "path must begin at /root: $path")
        return segs
    }

    // ── Op dispatch ──────────────────────────────────────────────────────────

    private fun applyOp(root: JSONObject, op: PatchOp) {
        val segs = parsePath(op.path)
        when (op) {
            is PatchOp.Set    -> setAt   (root, segs, op.value)
            is PatchOp.Insert -> insertAt(root, segs, op.value)
            is PatchOp.Remove -> removeAt(root, segs)
        }
    }

    // ── set ──────────────────────────────────────────────────────────────────

    /**
     * Set the value at [segs]. Handles three cases:
     *   - Path ends at an array index: replace that array element (whole node).
     *   - Path ends at a prop name under `props`: replace that prop value.
     *   - Path ends at "root" itself: replace the whole root node.
     */
    private fun setAt(root: JSONObject, segs: List<String>, value: Any?) {
        if (segs.size == 1) {
            // /root → replace root fields entirely
            requireNotNull(value) { "cannot set /root to null" }
            val new = (value as? JSONObject) ?: jsonObjOf(value)
                ?: fail("schema_mismatch", "/root replacement must be an object")
            // Clear root and copy in new keys.
            root.keys().asSequence().toList().forEach { root.remove(it) }
            new.keys().forEach { root.put(it, new.get(it)) }
            return
        }
        val parent = walkTo(root, segs.dropLast(1))
        val last = segs.last()
        when (parent) {
            is JSONArray -> {
                val idx = last.toIntOrNull()
                    ?: fail("patch_path_invalid", "expected array index at tail: $last")
                if (idx < 0 || idx >= parent.length()) {
                    fail("patch_path_invalid", "index $idx out of bounds (size=${parent.length()})")
                }
                parent.put(idx, jsonValue(value))
            }
            is JSONObject -> {
                parent.put(last, jsonValue(value))
            }
            else -> fail("patch_path_invalid", "cannot set on ${parent::class.simpleName}")
        }
    }

    // ── insert ───────────────────────────────────────────────────────────────

    /**
     * Insert into a `children` array at the specified index. Spec requires
     * the last two segments to be `children/<i>`.
     */
    private fun insertAt(root: JSONObject, segs: List<String>, value: Any?) {
        requireNotNull(value) { "insert requires a value" }
        val nodeJson = (value as? JSONObject)
            ?: fail("schema_mismatch", "insert value must be a node object")
        if (segs.size < 3 || segs[segs.size - 2] != "children") {
            fail("patch_path_invalid", "insert path must end with /children/<i>")
        }
        val idx = segs.last().toIntOrNull()
            ?: fail("patch_path_invalid", "insert index not numeric: ${segs.last()}")
        val parentNode = walkTo(root, segs.dropLast(2)) as? JSONObject
            ?: fail("patch_path_invalid", "insert parent is not an object")
        val arr = parentNode.optJSONArray("children") ?: JSONArray().also {
            parentNode.put("children", it)
        }
        if (idx < 0 || idx > arr.length()) {
            fail("patch_path_invalid", "insert index $idx out of bounds (size=${arr.length()})")
        }
        // JSONArray.put(index, value) extends the array if idx == length.
        // For idx < length we need to shift right. Simplest: rebuild.
        val rebuilt = JSONArray()
        for (i in 0 until idx) rebuilt.put(arr.get(i))
        rebuilt.put(nodeJson)
        for (i in idx until arr.length()) rebuilt.put(arr.get(i))
        parentNode.put("children", rebuilt)
    }

    // ── remove ───────────────────────────────────────────────────────────────

    /**
     * Remove the value at [segs]. Array indices shift later siblings left.
     */
    private fun removeAt(root: JSONObject, segs: List<String>) {
        if (segs.size == 1) fail("patch_path_invalid", "cannot remove /root")
        val parent = walkTo(root, segs.dropLast(1))
        val last = segs.last()
        when (parent) {
            is JSONArray -> {
                val idx = last.toIntOrNull()
                    ?: fail("patch_path_invalid", "expected array index at tail: $last")
                if (idx < 0 || idx >= parent.length()) {
                    fail("patch_path_invalid", "remove index $idx out of bounds (size=${parent.length()})")
                }
                parent.remove(idx)
            }
            is JSONObject -> parent.remove(last)
            else -> fail("patch_path_invalid", "cannot remove on ${parent::class.simpleName}")
        }
    }

    // ── Walk ─────────────────────────────────────────────────────────────────

    /**
     * Follow [segs] from [root] and return the addressed value. Never
     * descends past the last segment — caller does the final hop.
     */
    private fun walkTo(root: JSONObject, segs: List<String>): Any {
        require(segs.isNotEmpty()) { "walkTo: empty segments" }
        require(segs.first() == "root") { "walkTo: first segment must be 'root'" }

        var cursor: Any = root
        for (seg in segs.drop(1)) {
            cursor = when (cursor) {
                is JSONObject -> {
                    if (!cursor.has(seg)) fail("patch_path_invalid", "no key '$seg' on node")
                    cursor.get(seg)
                }
                is JSONArray -> {
                    val idx = seg.toIntOrNull()
                        ?: fail("patch_path_invalid", "expected numeric index, got '$seg'")
                    if (idx < 0 || idx >= cursor.length()) {
                        fail("patch_path_invalid", "index $idx out of bounds (size=${cursor.length()})")
                    }
                    cursor.get(idx)
                }
                else -> fail("patch_path_invalid", "cannot descend into ${cursor::class.simpleName}")
            }
        }
        return cursor
    }

    // ── Value helpers ────────────────────────────────────────────────────────

    /**
     * Convert a value from [PatchOp.Set.value] to something JSONObject.put
     * will accept. Accepts primitives, JSONObject, JSONArray, or a Map/List
     * that we convert.
     */
    private fun jsonValue(v: Any?): Any {
        return when (v) {
            null                -> JSONObject.NULL
            is JSONObject       -> v
            is JSONArray        -> v
            is Map<*, *>        -> JSONObject(v as Map<*, *>)
            is List<*>          -> JSONArray(v)
            is String, is Number, is Boolean -> v
            else -> v.toString()    // fall-through — serialize unknowns as string
        }
    }

    /** Best-effort coerce an arbitrary value to a JSONObject (for `/root` replacement). */
    private fun jsonObjOf(v: Any?): JSONObject? = when (v) {
        is JSONObject -> v
        is Map<*, *>  -> JSONObject(v as Map<*, *>)
        else          -> null
    }

    private fun fail(errorType: String, message: String): Nothing =
        throw PatchException(errorType, message)
}
