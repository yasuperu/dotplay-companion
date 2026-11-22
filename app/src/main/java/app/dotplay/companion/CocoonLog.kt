package app.dotplay.companion

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Live Cocoon tracking.
 *
 * Cocoon writes every game start to `Cocoon/launch_debug.log`:
 *   [2026-09-09 20:57:27.909] [----] ══════ Launch: Loco Roco USA PSP-pSyPSP ══════
 *   [2026-09-09 20:57:27.914] [INFO] Game: … | File: Loco_Roco_USA_PSP-pSyPSP.iso
 *   [2026-09-09 20:57:27.915] [INFO] ROM URI: content://…/primary%3AROMs%2Fpsp%2F…
 *   [2026-09-09 20:57:27.916] [INFO] Player: PPSSPP (org.ppsspp.ppsspp/.PpssppActivity)
 *
 * The log has no durations, so we combine it with Android's usage stats:
 * foreground time of an emulator package is credited to the last game Cocoon launched in it.
 */
object CocoonLog {
    data class Launch(val at: Long, val title: String, val system: String, val pkg: String)

    private val TS = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.\d+]""")
    // "Launch:" starts a game; "Swap:" switches the game inside an emulator that is already running
    private val LAUNCH = Regex("""(?:Launch|Swap): (.+?) ═+\s*$""")
    private val ROM = Regex("""ROMs%2F([^%/]+)%2F""", RegexOption.IGNORE_CASE)
    private val PLAYER = Regex("""Player: .*\(([a-zA-Z0-9_.]+)/""")

    /**
     * Max gap between Cocoon launching a game and emulator time we still credit to it.
     * Emulators stay open in the background and are often resumed days later from Recents
     * without a new launch in Cocoon, so this is generous.
     */
    private const val MAX_SESSION_MS = 21L * 24 * 3600_000

    fun parse(text: String): List<Launch> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val out = mutableListOf<Launch>()
        var at = 0L; var title: String? = null; var system = ""; var pkg: String? = null
        fun flush() {
            val t = title; val p = pkg
            if (t != null && p != null) out += Launch(at, cleanTitle(t), systemName(system), p)
            title = null; system = ""; pkg = null
        }
        for (line in text.lineSequence()) {
            val launch = LAUNCH.find(line)
            if (launch != null) {
                flush()
                at = TS.find(line)?.groupValues?.get(1)?.let { runCatching { fmt.parse(it)?.time }.getOrNull() } ?: 0L
                title = launch.groupValues[1].trim()
                continue
            }
            if (title == null) continue
            if (system.isEmpty()) ROM.find(line)?.let { system = it.groupValues[1] }
            if (pkg == null) PLAYER.find(line)?.let { pkg = it.groupValues[1] }
        }
        flush()
        return out.filter { it.at > 0 }.sortedBy { it.at }
    }

    /**
     * Credit emulator foreground spans to the game Cocoon launched last in that emulator.
     * Returns one PlayItem per game with minutes per day.
     */
    fun attribute(launches: List<Launch>, spans: Map<String, List<UsageTracker.Span>>): List<PlayItem> {
        val byPkg = launches.groupBy { it.pkg }
        val perGame = HashMap<Pair<String, String>, HashMap<String, Long>>()
        val last = HashMap<Pair<String, String>, Long>()
        for ((pkg, list) in spans) {
            val ls = byPkg[pkg] ?: continue
            for (whole in list) {
                // a Swap (or new launch) while the emulator stays on screen splits the span: time after it is the new game's
                val cuts = ls.map { it.at }.filter { it > whole.start + 60_000 && it < whole.end }
                val bounds = listOf(whole.start) + cuts + whole.end
                for (i in 0 until bounds.size - 1) {
                    val s = UsageTracker.Span(bounds[i], bounds[i + 1])
                    // allow a minute of clock slack: the log line is written just before the emulator resumes
                    val l = ls.lastOrNull { it.at <= s.start + 60_000 } ?: continue
                    if (s.start - l.at > MAX_SESSION_MS) continue
                    val key = l.title to l.system
                    UsageTracker.addSpan(perGame.getOrPut(key) { HashMap() }, s.start, s.end)
                    last[key] = maxOf(last[key] ?: 0L, s.end)
                }
            }
        }
        return perGame.mapNotNull { (key, byDay) ->
            val minutes = byDay.mapValues { (it.value / 60000).toInt() }.filterValues { it > 0 }
            if (minutes.isEmpty()) null
            else PlayItem(
                title = key.first,
                // GameNative runs your Steam games on Android: count it on the game's Steam entry
                platformType = if (key.second == "GameNative") "steam" else "emulated",
                platform = if (key.second == "GameNative") "Steam" else key.second,
                source = "cocoon",
                days = minutes,
                lastPlayed = last[key],
            )
        }
    }

    /** "Loco Roco USA PSP-pSyPSP" -> "Loco Roco", "Burnout 3 - Takedown" -> "Burnout 3: Takedown" */
    fun cleanTitle(raw: String): String {
        var s = raw.replace('_', ' ')
        s = s.replace(Regex("""\s*[\[(][^\])]*[\])]"""), "")                   // [01001F…] (ROMSLAB) (USA)
        s = s.replace(Regex("""\.(iso|nsp|xci|nds|3ds|cia|chd|cso|rvz|wbfs|zip|7z)$""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s+(USA|EUR|JPN|PAL|NTSC-?[UJ]?|US|EU|JP)(\s+.*)?$"""), "") // region + scene group
        s = s.replace(Regex("""\s+[A-Z0-9]{2,5}-[A-Za-z0-9]+$"""), "")              // "PSP-BAHAMUT"
        s = s.replace(" - ", ": ")
        return s.replace(Regex("""\s{2,}"""), " ").trim().ifEmpty { raw }
    }

    /** ROM folder name -> short system label used by Dotplay. */
    fun systemName(folder: String): String = when (folder.lowercase()) {
        "" -> "Emulated"
        "steam", "gamenative", "windows", "pc" -> "GameNative"
        "n3ds", "3ds" -> "3DS"
        "nds", "ds" -> "DS"
        "ps2" -> "PS2"
        "psx", "ps1", "psone" -> "PS1"
        "psp" -> "PSP"
        "psvita", "vita" -> "PS Vita"
        "switch" -> "Switch"
        "wii" -> "Wii"
        "wiiu" -> "Wii U"
        "gc", "ngc", "gamecube" -> "GameCube"
        "n64" -> "N64"
        "snes", "sfc" -> "SNES"
        "nes", "fc" -> "NES"
        "gba" -> "GBA"
        "gbc" -> "GBC"
        "gb" -> "Game Boy"
        "dc", "dreamcast" -> "Dreamcast"
        "saturn" -> "Saturn"
        "megadrive", "genesis", "md" -> "Genesis"
        else -> CocoonImport.cleanPlatform(folder)
    }

    // ---------- reading the log through the folder the user granted ----------

    /** Read launch_debug.log from the Cocoon folder tree the user picked (Storage Access Framework). */
    fun readLog(ctx: Context, tree: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "launch_debug.log") {
                    val file = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    return ctx.contentResolver.openInputStream(file)?.use { it.bufferedReader().readText() }
                }
            }
        }
        return null
    }

    /** Everything needed for one sync: parse the log, read usage, attribute. */
    fun collect(ctx: Context, prefs: Prefs, days: Int = 7, skipPackages: Set<String> = emptySet()): List<PlayItem> {
        val tree = prefs.cocoonTree ?: return emptyList()
        val text = readLog(ctx, Uri.parse(tree)) ?: return emptyList()
        val launches = parse(text).filter { it.pkg !in skipPackages }   // mapped apps are counted by their mapping
        if (launches.isEmpty()) return emptyList()
        val spans = UsageTracker.spans(ctx, launches.map { it.pkg }.toSet(), days)
        return attribute(launches, spans)
    }
}
