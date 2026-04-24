package local.skippy.droid.features.services

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.hudFont
import local.skippy.droid.compositor.hudSp
import local.skippy.droid.compositor.passthrough.AppRegistry
import local.skippy.droid.compositor.passthrough.PassthroughHost
import local.skippy.droid.layers.FeatureModule
import kotlinx.coroutines.delay

/**
 * Session 11e — Services panel.
 *
 * A tiny TopStart tile list showing every view that's currently registered
 * with [PassthroughHost.registry]. Active view renders in
 * [HudPalette.Green]; idle-but-registered views render in [HudPalette.White];
 * when the registry is empty we paint a single dim placeholder so the panel
 * reserves its slot (no layout jitter when DJ / Star Map connect).
 *
 * ── Interaction ──────────────────────────────────────────────────────────
 * Phone mirror: tap a tile → [PassthroughHost.activate] mounts that view
 * into the Viewport (the existing active mount gets its `before_unmount`
 * grace per PROTOCOL §8). Glasses: no tap affordance — voice only.
 *
 * ── Voice integration ───────────────────────────────────────────────────
 * Registration + activation-by-name are wired in [MainActivity] via the
 * `services.list` and `services.open` intents. This module only renders.
 *
 * ── Observability of the registry ────────────────────────────────────────
 * [AppRegistry.active] is Compose-observable out of the box
 * (mutableStateOf) but [AppRegistry.list] reads a ConcurrentHashMap that
 * isn't. Rather than retrofit a Flow into AppRegistry, we tick every 500ms
 * and re-snapshot — cheap, Phase-1-appropriate, and survives producers
 * connecting at arbitrary moments.
 */
class ServicesPanelModule(
    private val host: PassthroughHost,
) : FeatureModule {

    override val id = "local.skippy.services"
    override var enabled: Boolean by mutableStateOf(true)
    override val zone: HudZone = HudZone.TopStart
    // Sits ABOVE SpeedModule (zOrder 9) in TopStart — we want the services
    // list to read first, since it's the "what can I open right now" header.
    override val zOrder: Int = 3
    override val requiresNetwork: Boolean = false

    // Shadow state that tracks the registry snapshot. Updated by the tick
    // loop in [Overlay]; the Compose recomposition flows from here.
    private var snapshot by mutableStateOf<List<AppRegistry.RegisteredView>>(emptyList())
    private var activeId by mutableStateOf<String?>(null)

    @Composable
    override fun Overlay() {
        LaunchedEffect(Unit) {
            while (true) {
                snapshot = host.registry.list()
                activeId = host.registry.active?.view?.id
                delay(500L)
            }
        }

        Column {
            Text(
                text = "SERVICES",
                color = HudPalette.DimGreenHi,
                fontSize = hudSp(0.7f),
                fontFamily = hudFont,
                fontWeight = FontWeight.Bold,
            )
            if (snapshot.isEmpty()) {
                Text(
                    text = "— none —",
                    color = HudPalette.DimGreenHi,
                    fontSize = hudSp(0.85f),
                    fontFamily = hudFont,
                )
            } else {
                snapshot.forEach { view ->
                    val isActive = view.id == activeId
                    Text(
                        text = (if (isActive) "▸ " else "  ") + view.name,
                        color = if (isActive) HudPalette.Green else HudPalette.White,
                        fontSize = hudSp(0.9f),
                        fontFamily = hudFont,
                        modifier = Modifier
                            .width(220.dp)
                            .padding(vertical = 6.dp)
                            .clickable {
                                Log.i("Local.Skippy.Services", "tile tapped: ${view.id}")
                                host.activate(view.id)
                            },
                    )
                }
            }
        }
    }

    /**
     * Glasses mirror paints the same thing minus the clickable affordance —
     * the Compose Modifier.clickable is harmless on a non-touch display but
     * we skip it for cleanliness. The voice path covers activation here.
     */
    @Composable
    override fun GlassesOverlay() {
        LaunchedEffect(Unit) {
            while (true) {
                snapshot = host.registry.list()
                activeId = host.registry.active?.view?.id
                delay(500L)
            }
        }

        Column {
            Text(
                text = "SERVICES",
                color = HudPalette.DimGreenHi,
                fontSize = hudSp(0.7f),
                fontFamily = hudFont,
                fontWeight = FontWeight.Bold,
            )
            if (snapshot.isEmpty()) {
                Text(
                    text = "— none —",
                    color = HudPalette.DimGreenHi,
                    fontSize = hudSp(0.85f),
                    fontFamily = hudFont,
                )
            } else {
                snapshot.forEach { view ->
                    val isActive = view.id == activeId
                    Text(
                        text = (if (isActive) "▸ " else "  ") + view.name,
                        color = if (isActive) HudPalette.Green else HudPalette.White,
                        fontSize = hudSp(0.9f),
                        fontFamily = hudFont,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
