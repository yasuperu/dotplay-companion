package app.dotplay.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Count this app as that game": for apps Cocoon can't identify by itself, such as a Switch 2 emulator
 * that runs one game (Banana Native = Pokémon Pokopia), PC recomps (Ship of Harkinian = Ocarina of Time)
 * or Android ports. Every mapping can be switched off without deleting it; when off, the app is tracked
 * as itself again (only if it is in the tracked Android games).
 */
data class GameMapping(
    val pkg: String,
    val appLabel: String,
    val title: String,
    val platformType: String,   // emulated | pc | console | mobile | steam
    val platform: String,       // e.g. "Switch 2", "N64", "PC"
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("appLabel", appLabel).put("title", title)
        .put("platformType", platformType).put("platform", platform).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = GameMapping(
            pkg = o.getString("pkg"),
            appLabel = o.optString("appLabel", o.getString("pkg")),
            title = o.getString("title"),
            platformType = o.optString("platformType", "emulated"),
            platform = o.optString("platform", ""),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

object GameMappings {
    private const val KEY = "gameMappings"

    fun all(ctx: Context): List<GameMapping> {
        val raw = ctx.getSharedPreferences("dotplay", Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { GameMapping.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedBy { it.title.lowercase() }
    }

    private fun save(ctx: Context, list: List<GameMapping>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        ctx.getSharedPreferences("dotplay", Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun put(ctx: Context, m: GameMapping) = save(ctx, all(ctx).filter { it.pkg != m.pkg } + m)

    fun remove(ctx: Context, pkg: String) = save(ctx, all(ctx).filter { it.pkg != pkg })

    fun active(ctx: Context): Map<String, GameMapping> = all(ctx).filter { it.enabled }.associateBy { it.pkg }

    /** Pre-filled suggestions for well-known apps; always editable. Matched on the app's name. */
    private val SUGGESTIONS = listOf(
        Regex("banana", RegexOption.IGNORE_CASE) to Triple("Pokémon Pokopia", "emulated", "Switch 2"),
        Regex("^2 ?ship 2 ?harkinian", RegexOption.IGNORE_CASE) to Triple("The Legend of Zelda: Majora's Mask", "pc", "N64 recomp"),
        Regex("ship of harkinian", RegexOption.IGNORE_CASE) to Triple("The Legend of Zelda: Ocarina of Time", "pc", "N64 recomp"),
        Regex("zelda ?64 ?recomp", RegexOption.IGNORE_CASE) to Triple("The Legend of Zelda: Majora's Mask", "pc", "N64 recomp"),
        Regex("starship", RegexOption.IGNORE_CASE) to Triple("Star Fox 64", "pc", "N64 recomp"),
        Regex("sm64|super mario 64", RegexOption.IGNORE_CASE) to Triple("Super Mario 64", "pc", "N64 recomp"),
        Regex("perfect dark", RegexOption.IGNORE_CASE) to Triple("Perfect Dark", "pc", "N64 recomp"),
        Regex("spaghetti ?kart", RegexOption.IGNORE_CASE) to Triple("Mario Kart 64", "pc", "N64 recomp"),
    )

    fun suggest(appLabel: String): Triple<String, String, String>? =
        SUGGESTIONS.firstOrNull { it.first.containsMatchIn(appLabel) }?.second
}
