package com.skippy.droid.compositor.passthrough

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM-pure tests for [ScenePatchApplier]. Exercise the contract in
 * PROTOCOL §6 without spinning up Android. Real `org.json` is on the test
 * classpath via `testImplementation libs.org.json`.
 */
class ScenePatchApplierTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A minimal valid scene: Box with one Text child. */
    private fun simpleScene(): Scene {
        val root = JSONObject().apply {
            put("type", "Box")
            put("id", "root")
            put("children", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "Text")
                    put("id", "hello")
                    put("props", JSONObject().apply {
                        put("text", "hi")
                        put("size_px", 22)
                        put("color", "white")
                    })
                })
            })
        }
        return Scene(root = SceneJson.parseNode(root), seq = 0)
    }

    // ── set ──────────────────────────────────────────────────────────────────

    @Test fun `set replaces a prop by path`() {
        val scene = simpleScene()
        val next = ScenePatchApplier.apply(scene, listOf(
            PatchOp.Set("/root/children/0/props/text", "hello world")
        ))
        val textNode = (next.root as SceneNode.Box).children.first() as SceneNode.Text
        assertEquals("hello world", textNode.text)
        assertEquals(1L, next.seq)
    }

    @Test fun `set with palette enum stays typed`() {
        val scene = simpleScene()
        val next = ScenePatchApplier.apply(scene, listOf(
            PatchOp.Set("/root/children/0/props/color", "amber")
        ))
        val textNode = (next.root as SceneNode.Box).children.first() as SceneNode.Text
        assertEquals(PaletteEnum.Amber, textNode.color)
    }

    @Test fun `set off-palette value fails as schema_mismatch`() {
        val scene = simpleScene()
        try {
            ScenePatchApplier.apply(scene, listOf(
                PatchOp.Set("/root/children/0/props/color", "#FF00AA")
            ))
            fail("expected PatchException")
        } catch (e: ScenePatchApplier.PatchException) {
            assertEquals("schema_mismatch", e.errorType)
        }
    }

    // ── insert ───────────────────────────────────────────────────────────────

    @Test fun `insert at tail appends child`() {
        val scene = simpleScene()
        val newChild = JSONObject().apply {
            put("type", "Text")
            put("id", "world")
            put("props", JSONObject().apply {
                put("text", "bye")
                put("size_px", 22)
                put("color", "cyan")
            })
        }
        val next = ScenePatchApplier.apply(scene, listOf(
            PatchOp.Insert("/root/children/1", newChild)
        ))
        val kids = (next.root as SceneNode.Box).children
        assertEquals(2, kids.size)
        assertEquals("world", (kids[1] as SceneNode.Text).id)
    }

    @Test fun `insert at head shifts siblings right`() {
        val scene = simpleScene()
        val newChild = JSONObject().apply {
            put("type", "Text"); put("id", "head")
            put("props", JSONObject().apply {
                put("text", "X"); put("size_px", 22); put("color", "green")
            })
        }
        val next = ScenePatchApplier.apply(scene, listOf(
            PatchOp.Insert("/root/children/0", newChild)
        ))
        val kids = (next.root as SceneNode.Box).children
        assertEquals(2, kids.size)
        assertEquals("head",  (kids[0] as SceneNode.Text).id)
        assertEquals("hello", (kids[1] as SceneNode.Text).id)
    }

    @Test fun `insert with bad path raises patch_path_invalid`() {
        val scene = simpleScene()
        try {
            ScenePatchApplier.apply(scene, listOf(
                PatchOp.Insert("/root/props/fake", JSONObject().put("type", "Box"))
            ))
            fail("expected PatchException")
        } catch (e: ScenePatchApplier.PatchException) {
            assertEquals("patch_path_invalid", e.errorType)
        }
    }

    // ── remove ───────────────────────────────────────────────────────────────

    @Test fun `remove shifts later siblings left`() {
        val scene = simpleScene()
        val extra = JSONObject().apply {
            put("type", "Text"); put("id", "gone")
            put("props", JSONObject().apply {
                put("text", "Y"); put("size_px", 22); put("color", "red")
            })
        }
        val afterInsert = ScenePatchApplier.apply(scene, listOf(
            PatchOp.Insert("/root/children/0", extra)
        ))
        val afterRemove = ScenePatchApplier.apply(afterInsert, listOf(
            PatchOp.Remove("/root/children/0")
        ))
        val kids = (afterRemove.root as SceneNode.Box).children
        assertEquals(1, kids.size)
        assertEquals("hello", (kids[0] as SceneNode.Text).id)
    }

    @Test fun `remove of root itself fails`() {
        val scene = simpleScene()
        try {
            ScenePatchApplier.apply(scene, listOf(PatchOp.Remove("/root")))
            fail("expected PatchException")
        } catch (e: ScenePatchApplier.PatchException) {
            assertEquals("patch_path_invalid", e.errorType)
        }
    }

    // ── atomic / seq ─────────────────────────────────────────────────────────

    @Test fun `seq advances by ops length on success`() {
        val scene = simpleScene()
        val next = ScenePatchApplier.apply(scene, listOf(
            PatchOp.Set("/root/children/0/props/text", "a"),
            PatchOp.Set("/root/children/0/props/text", "b"),
            PatchOp.Set("/root/children/0/props/text", "c"),
        ))
        assertEquals(3L, next.seq)
    }

    @Test fun `empty ops is a no-op`() {
        val scene = simpleScene()
        val next = ScenePatchApplier.apply(scene, emptyList())
        assertTrue(scene === next || scene.seq == next.seq)
    }

    @Test fun `failure mid-batch leaves scene unchanged`() {
        val scene = simpleScene()
        try {
            ScenePatchApplier.apply(scene, listOf(
                PatchOp.Set("/root/children/0/props/text", "ok"),
                // This one fails — bad path.
                PatchOp.Set("/root/nope/no", "x"),
            ))
            fail("expected PatchException")
        } catch (e: ScenePatchApplier.PatchException) {
            assertNotNull(e.message)
        }
        // Scene state preserved: still says "hi".
        val textNode = (scene.root as SceneNode.Box).children.first() as SceneNode.Text
        assertEquals("hi", textNode.text)
    }
}
