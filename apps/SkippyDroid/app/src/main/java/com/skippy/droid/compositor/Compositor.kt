package com.skippy.droid.compositor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.skippy.droid.layers.ContextEngine
import com.skippy.droid.layers.FeatureModule

/**
 * Layer 6 — Compositor.
 *
 * Renders all feature modules that are:
 *   1. [FeatureModule.enabled] == true (Captain's manual toggle)
 *   2. Active in the current [ContextEngine.Mode] — or declare no mode preference
 *
 * ── Zone-driven layout (Session 7 doctrine) ───────────────────────────────
 *
 * The root is a Box clamped to the canonical **1920×1200 dp** canvas
 * (VITURE Luma Ultra native resolution) and centered in the parent. On the
 * S23 landscape the parent may be slightly wider and shorter — the clamp
 * letterboxes accordingly (unused area is pure black, which reads as
 * transparent on the glasses).
 *
 * Render passes, in order:
 *
 *   1. [HudZone.Fullscreen] — AR canvases (nav dots, green arrow). Paint first
 *      so chrome layers stack on top. Fill the clamped 1920×1200 area, NOT
 *      the raw screen — this keeps world-projection math aligned with the
 *      chrome frame regardless of device.
 *
 *   2. [HudZone.Viewport] — the reserved 1540×960 center rectangle. At most
 *      one module may claim it; today that's empty (future: mounted passthrough
 *      apps per PASSTHROUGH_STANDARD.md). If no module claims Viewport, the
 *      nav Canvas from pass 1 fills this space naturally.
 *
 *   3. Chrome zones — one positioning Box per zone. The six text zones +
 *      the two symbology sidebars each paint in their own Box. Sibling
 *      modules within a zone stack in a Column by zOrder (lowest = nearest
 *      anchor edge; subsequent modules pile away from it).
 *
 * [isGlasses] routes to [FeatureModule.GlassesOverlay] (glasses display) vs
 * [FeatureModule.Overlay] (phone mirror). With the phone being the full-time
 * glasses mirror the two typically resolve to the same composable, but
 * modules like NavigationModule still distinguish (phone shows error only,
 * glasses shows Canvas).
 *
 * [letterboxColor] paints around the clamped canvas when the parent is a
 * different aspect ratio from the glasses (16:10). The glasses display is
 * exactly 1920×1200 so no letterbox shows — default stays [HudPalette.Black]
 * (which is additive-transparent on the glasses). The phone mirror is
 * typically ~19.5:9 (wider than 16:10), so it passes
 * [HudPalette.MirrorLetterbox] for a visible blue frame around the glasses
 * canvas — that way the Captain sees exactly what the glasses see, with no
 * horizontal stretch at the edges.
 *
 * [backdrop] fills the 16:10 inner canvas BEHIND every HUD pass — whatever it
 * paints shows through wherever the modules leave pixels untouched. Default
 * is solid [HudPalette.Black], which is the right answer for the glasses
 * (black = transparent on the additive-light display) and for the phone
 * mirror while the glasses are attached (chrome-only preview). The phone
 * mirror swaps in a camera-preview backdrop when glasses are NOT attached —
 * the phone then acts as a stand-in viewfinder, showing what the Captain
 * would see through the lenses with the HUD composited on top.
 */
@Composable
fun Compositor(
    modules: List<FeatureModule>,
    context: ContextEngine,
    isGlasses: Boolean = false,
    letterboxColor: Color = HudPalette.Black,
    backdrop: @Composable BoxScope.() -> Unit = { DefaultBlackBackdrop() },
) {
    val mode = context.currentMode   // mutableStateOf — drives recomposition on mode change

    val active = modules
        .filter { it.enabled }
        .filter { it.activeIn.isEmpty() || mode in it.activeIn }
        .sortedBy { it.zOrder }

    // Outer Box: fills the whole display and paints the letterbox color
    // behind the clamped canvas. On the glasses (exactly 1920×1200) the
    // inner canvas fills the outer box and no letterbox is visible. On the
    // phone (wider than 16:10) the aspectRatio clamp leaves margins where
    // this color shows through.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(letterboxColor),
        contentAlignment = Alignment.Center,
    ) {
        // Inner Box: the canonical 1920×1200 design canvas, centered.
        //
        // `aspectRatio(1920/1200)` enforces the glasses' 16:10 shape so the
        // HUD never stretches horizontally on non-matching parents — critical
        // for the phone mirror to be a faithful preview of what the Captain
        // sees through the lenses. `sizeIn(max=…)` caps us at the glasses'
        // native pixel grid; on smaller parents the box shrinks to fit (the
        // fitting primitives hudSp / SizeJustifiedText scale zone budgets
        // proportionally).
        //
        // Why no `fillMaxSize()` here: fillMaxSize pins both min and max
        // constraints to the parent's size, which leaves aspectRatio nothing
        // to negotiate with. Without it, aspectRatio receives
        // `min=0, max=parent` and picks the largest 16:10 sub-rectangle that
        // fits — exactly what we want.
        Box(
            modifier = Modifier
                .aspectRatio(1920f / 1200f)
                .sizeIn(maxWidth = 1920.dp, maxHeight = 1200.dp),
        ) {
            // ── Pass 0: Backdrop ────────────────────────────────────────────
            // Paints beneath every HUD layer. Default is solid black (right
            // for glasses + phone-mirror-with-glasses-attached). Phone mirror
            // with glasses detached passes a CameraPassthrough so the phone
            // becomes a stand-in viewfinder.
            backdrop()

            // ── Pass 1: Fullscreen modules (AR canvases, self-placing) ──────
            // These fill the clamped canvas. Within this pass zOrder controls
            // stack order — higher zOrder paints on top.
            active.filter { it.zone == HudZone.Fullscreen }.forEach { mod ->
                if (isGlasses) mod.GlassesOverlay() else mod.Overlay()
            }

            // ── Pass 2: Viewport — at most one module claims it ─────────────
            // Reserved 1540×960 center rectangle. Phase 1: no modules claim
            // this, so the Fullscreen AR canvas from pass 1 fills it naturally.
            // Phase 2: mounted passthrough apps (DJ Block Planner, etc.) will
            // land here via a WebView host module.
            val viewportModules = active.filter { it.zone == HudZone.Viewport }
            if (viewportModules.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val budget = HudZone.Viewport.budget
                    Box(
                        modifier = Modifier.sizeIn(
                            maxWidth = budget.width ?: 1540.dp,
                            maxHeight = budget.height ?: 960.dp,
                        ),
                    ) {
                        viewportModules.forEach { mod ->
                            if (isGlasses) mod.GlassesOverlay() else mod.Overlay()
                        }
                    }
                }
            }

            // ── Pass 3: Chrome zones (6 text + 2 symbology sidebars) ────────
            // One positioning Box per zone. Modules sharing a zone stack
            // vertically in zOrder.
            for (zone in CHROME_ZONES) {
                val zoneModules = active.filter { it.zone == zone }
                if (zoneModules.isEmpty()) continue
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(zone.edgePadding),
                    contentAlignment = zone.contentAlignment,
                ) {
                    Column(horizontalAlignment = zone.columnAlignment) {
                        zoneModules.forEach { mod ->
                            if (isGlasses) mod.GlassesOverlay() else mod.Overlay()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The eight "chrome" zones — every zone except [HudZone.Fullscreen] and
 * [HudZone.Viewport]. Rendered as standard positioning Boxes in pass 3.
 *
 * Order here affects paint stacking when zones happen to overlap at the
 * edges (shouldn't normally); the top-row zones are listed first so they
 * end up underneath the sidebars if the sidebars extend into the corners.
 */
private val CHROME_ZONES: List<HudZone> = listOf(
    HudZone.TopStart, HudZone.TopCenter, HudZone.TopEnd,
    HudZone.BottomStart, HudZone.BottomCenter, HudZone.BottomEnd,
    HudZone.LeftBar, HudZone.RightBar,
)

/**
 * Default backdrop — solid black fill. Black is additive-transparent on
 * the VITURE's additive-light display (glasses show the real world through
 * it) and is the neutral chrome-only read on the phone mirror when the
 * glasses are attached. Pulled out as a named composable so tests /
 * screenshots can reference the exact same primitive.
 */
@Composable
private fun BoxScope.DefaultBlackBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HudPalette.Black),
    )
}
