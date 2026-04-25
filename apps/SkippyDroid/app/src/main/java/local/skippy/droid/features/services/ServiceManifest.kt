package local.skippy.droid.features.services

/**
 * One service entry from SkippyTel's GET /services manifest.
 *
 * @param id             Machine-friendly identifier, e.g. "bilby"
 * @param name           Display name, e.g. "Bilby"
 * @param tagline        One-line description
 * @param available      Live availability from SkippyTel (SD online, Drive configured, etc.)
 * @param statusDetail   Human-readable availability reason, or null
 * @param displayMode    How the phone should surface this service:
 *                       "overlay" | "companion" | "inline" | "background"
 * @param hudZone        HUD zone name for overlay services, or null
 * @param companionApp   Android package to launch for companion services, or null
 * @param baseUrl        Direct Tailscale URL for the service, or null to proxy via SkippyTel.
 *                       When set, the phone should route API calls directly to this host
 *                       rather than through SkippyTel. Only effective on devices that can
 *                       reach Tailscale (real S23); emulator falls back to SkippyTel.
 * @param voiceTriggers  Phrases that activate this service. "*" = catch-all (AI).
 * @param endpoints      Short name → relative path, e.g. {"status":"/bilby/status"}
 */
data class ServiceManifest(
    val id:           String,
    val name:         String,
    val tagline:      String,
    val available:    Boolean,
    val statusDetail: String?,
    val displayMode:  String,
    val hudZone:      String?,
    val companionApp: String?,
    val baseUrl:      String?,
    val voiceTriggers: List<String>,
    val endpoints:    Map<String, String>,
) {
    /** True for services the user can interact with right now. */
    val isActionable: Boolean get() = available && displayMode != "background"

    companion object {
        fun fromJson(obj: org.json.JSONObject): ServiceManifest {
            val display = obj.optJSONObject("display") ?: org.json.JSONObject()
            val triggers = mutableListOf<String>()
            val ta = obj.optJSONArray("voice_triggers")
            if (ta != null) for (i in 0 until ta.length()) triggers += ta.getString(i)
            val endpoints = mutableMapOf<String, String>()
            val ep = obj.optJSONObject("endpoints")
            if (ep != null) ep.keys().forEach { k -> endpoints[k] = ep.getString(k) }

            return ServiceManifest(
                id           = obj.getString("id"),
                name         = obj.getString("name"),
                tagline      = obj.optString("tagline", ""),
                available    = obj.optBoolean("available", true),
                statusDetail = obj.optString("status_detail").takeIf { it.isNotBlank() },
                displayMode  = display.optString("mode", "background"),
                hudZone      = display.optString("hud_zone").takeIf { it.isNotBlank() },
                companionApp = display.optString("companion_app").takeIf { it.isNotBlank() },
                baseUrl      = obj.optString("base_url").takeIf { it.isNotBlank() },
                voiceTriggers = triggers,
                endpoints    = endpoints,
            )
        }
    }
}
