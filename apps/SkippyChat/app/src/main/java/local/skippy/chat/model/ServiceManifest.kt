package local.skippy.chat.model

import org.json.JSONObject

/**
 * One service entry from SkippyTel's GET /services manifest.
 *
 * Local copy of the SkippyDroid equivalent — quarantine doctrine forbids
 * cross-app imports. Shape is identical; keep in sync manually.
 *
 * @param id             Machine-friendly identifier, e.g. "bilby"
 * @param name           Display name, e.g. "Bilby"
 * @param tagline        One-line description
 * @param available      Live availability from SkippyTel
 * @param statusDetail   Human-readable availability reason, or null
 * @param displayMode    "overlay" | "companion" | "inline" | "background"
 * @param hudZone        HUD zone name for overlay services, or null
 * @param companionApp   Android package to launch for companion services, or null
 * @param baseUrl        Direct Tailscale URL for the service, or null to proxy via SkippyTel.
 *                       When set, route API calls to this host directly (real S23 on Tailscale).
 *                       Emulator always falls through to SkippyTel proxy.
 * @param voiceTriggers  Phrases that activate this service. "*" = catch-all (AI).
 * @param endpoints      Short name → relative path, e.g. {"status":"/bilby/status"}
 */
data class ServiceManifest(
    val id:            String,
    val name:          String,
    val tagline:       String,
    val available:     Boolean,
    val statusDetail:  String?,
    val displayMode:   String,
    val hudZone:       String?,
    val companionApp:  String?,
    val baseUrl:       String?,
    val voiceTriggers: List<String>,
    val endpoints:     Map<String, String>,
) {
    /** True for services the user can interact with right now. */
    val isActionable: Boolean get() = available && displayMode != "background"

    companion object {
        fun fromJson(obj: JSONObject): ServiceManifest {
            val display  = obj.optJSONObject("display") ?: JSONObject()
            val triggers = mutableListOf<String>()
            val ta = obj.optJSONArray("voice_triggers")
            if (ta != null) for (i in 0 until ta.length()) triggers += ta.getString(i)
            val endpoints = mutableMapOf<String, String>()
            val ep = obj.optJSONObject("endpoints")
            if (ep != null) ep.keys().forEach { k -> endpoints[k] = ep.getString(k) }

            return ServiceManifest(
                id            = obj.getString("id"),
                name          = obj.getString("name"),
                tagline       = obj.optString("tagline", ""),
                available     = obj.optBoolean("available", true),
                statusDetail  = obj.optString("status_detail").takeIf { it.isNotBlank() },
                displayMode   = display.optString("mode", "background"),
                hudZone       = display.optString("hud_zone").takeIf { it.isNotBlank() },
                companionApp  = display.optString("companion_app").takeIf { it.isNotBlank() },
                baseUrl       = obj.optString("base_url").takeIf { it.isNotBlank() },
                voiceTriggers = triggers,
                endpoints     = endpoints,
            )
        }
    }
}
