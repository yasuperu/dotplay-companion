package app.dotplay.companion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports playtime from a Cocoon "Play History" backup.
 *
 * Cocoon's backup format is not documented, so this parser is deliberately forgiving:
 * it accepts JSON, a ZIP containing JSON / SQLite files, or a SQLite database, and looks for
 *   - session records: { game/title/name, start/startTime, end/endTime | duration }  -> minutes per day
 *   - game records:    { title/name, playtime/playTime/totalPlaytime/duration }      -> total minutes
 * If your backup isn't recognised, open an issue with a (redacted) sample and the field names get added.
 */
object CocoonImport {

    data class Result(val items: List<PlayItem>, val note: String)

    private val TITLE_KEYS = listOf("title", "gameTitle", "game_title", "gameName", "game_name", "name", "displayName", "romName", "label")
    private val PLATFORM_KEYS = listOf("platform", "system", "systemName", "system_name", "console", "platformName", "core", "collection")
    private val TOTAL_KEYS = listOf("playtime", "playTime", "play_time", "totalPlaytime", "total_playtime", "totalPlayTime", "timePlayed", "time_played", "playtimeSeconds", "playtimeMs", "duration", "durationMs", "duration_ms", "seconds")
    private val START_KEYS = listOf("start", "startTime", "start_time", "startedAt", "started_at", "begin", "startTimestamp", "timestamp")
    private val END_KEYS = listOf("end", "endTime", "end_time", "endedAt", "ended_at", "stop", "endTimestamp")
    private val GAME_REF_KEYS = listOf("game", "gameId", "game_id", "romId", "rom_id", "gameKey")

    fun read(ctx: Context, uri: Uri): Result {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return Result(emptyList(), "Could not read file")
        return parseBytes(ctx, bytes, uri.lastPathSegment ?: "backup")
    }

    private fun parseBytes(ctx: Context, bytes: ByteArray, name: String): Result {
        // ZIP?
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val all = mutableListOf<PlayItem>()
            val notes = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && !e.name.lowercase().matches(Regex(".*\\.(png|jpg|jpeg|webp|gif)$"))) {
                        val r = parseBytes(ctx, zip.readBytes(), e.name)
                        if (r.items.isNotEmpty()) { all += r.items; notes += "${e.name}: ${r.items.size}" }
                    }
                    e = zip.nextEntry
                }
            }
            return Result(merge(all), if (notes.isEmpty()) "No playtime found in ZIP" else notes.joinToString())
        }
        // SQLite?
        if (bytes.size > 16 && String(bytes, 0, 15) == "SQLite format 3") return parseSqlite(ctx, bytes)
        // JSON?
        val text = String(bytes, Charsets.UTF_8).trim()
        if (text.startsWith("{") || text.startsWith("[")) {
            // Dotplay's own history format (e.g. read from Cocoon's Log pod): items are passed through as-is
            if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull()?.let { o ->
                if (o.optString("dotplay") == "cocoon-history") return parseDotplayHistory(o, name)
            }
            return runCatching {
                val root: Any = if (text.startsWith("{")) JSONObject(text) else JSONArray(text)
                val items = parseJsonTree(root)
                Result(merge(items), if (items.isEmpty()) "JSON read, but no playtime fields recognised in $name" else "Read $name")
            }.getOrElse { Result(emptyList(), "Invalid JSON in $name") }
        }
        return Result(emptyList(), "Unknown file type: $name")
    }

    // ---------- Dotplay history file ----------

    /** {"dotplay":"cocoon-history","items":[{title, platformType, platform, source, totalMinutes, lastPlayed, days:[{day, minutes}]}]} */
    private fun parseDotplayHistory(o: JSONObject, name: String): Result {
        val arr = o.optJSONArray("items") ?: return Result(emptyList(), "No items in $name")
        val items = (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = it.optString("title").takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null
            val days = HashMap<String, Int>()
            it.optJSONArray("days")?.let { d ->
                for (j in 0 until d.length()) d.optJSONObject(j)?.let { x -> days[x.optString("day")] = x.optInt("minutes") }
            }
            PlayItem(
                title = title,
                platformType = it.optString("platformType", "emulated"),
                platform = it.optString("platform", "Emulated"),
                source = it.optString("source", "cocoon"),
                days = days.filterKeys { k -> k.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) },
                totalMinutes = if (it.has("totalMinutes")) it.optInt("totalMinutes") else null,
                lastPlayed = it.optString("lastPlayed").takeIf { s -> s.isNotBlank() }?.let { s ->
                    runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
                },
            )
        }
        return Result(items.sortedByDescending { it.totalMinutes ?: 0 }, "Read $name (Dotplay history)")
    }

    // ---------- JSON ----------

    private fun parseJsonTree(root: Any): List<PlayItem> {
        val games = HashMap<String, Pair<String, String>>()  // id -> (title, platform)
        val objects = mutableListOf<JSONObject>()
        walk(root) { objects += it }
        // first pass: remember game objects by id, so sessions that only reference an id can be named
        for (o in objects) {
            val title = str(o, TITLE_KEYS) ?: continue
            val id = o.opt("id")?.toString() ?: o.opt("_id")?.toString() ?: o.opt("uuid")?.toString()
            if (id != null) games[id] = title to (str(o, PLATFORM_KEYS) ?: "")
        }
        val out = mutableListOf<PlayItem>()
        for (o in objects) {
            val start = time(o, START_KEYS)
            var title = str(o, TITLE_KEYS)
            var platform = str(o, PLATFORM_KEYS) ?: ""
            if (title == null) {
                val ref = GAME_REF_KEYS.firstNotNullOfOrNull { k -> o.opt(k)?.let { if (it is JSONObject) null else it.toString() } }
                val nested = GAME_REF_KEYS.firstNotNullOfOrNull { k -> o.optJSONObject(k) }
                if (nested != null) { title = str(nested, TITLE_KEYS); platform = str(nested, PLATFORM_KEYS) ?: platform }
                else if (ref != null) games[ref]?.let { title = it.first; platform = it.second }
            }
            if (title == null) continue
            if (start != null) {
                // Session record
                val end = time(o, END_KEYS)
                val mins = when {
                    end != null && end > start -> ((end - start) / 60000).toInt()
                    else -> duration(o) ?: 0
                }
                if (mins > 0) out += PlayItem(title!!, "emulated", cleanPlatform(platform), "cocoon",
                    days = mapOf(dayKey(start) to mins), lastPlayed = start + mins * 60000L)
            } else {
                val mins = duration(o) ?: continue
                if (mins > 0) out += PlayItem(title!!, "emulated", cleanPlatform(platform), "cocoon", totalMinutes = mins)
            }
        }
        return out
    }

    private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> { visit(node); node.keys().forEach { walk(node.opt(it), visit) } }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    private fun str(o: JSONObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it is String && it.isNotBlank() } as String? }

    /** Timestamps: epoch seconds, epoch millis or ISO strings. */
    private fun time(o: JSONObject, keys: List<String>): Long? {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            toMillis(v)?.let { return it }
        }
        return null
    }

    private fun toMillis(v: Any): Long? = when (v) {
        is Number -> v.toLong().let { if (it in 1_000_000_000L..9_999_999_999L) it * 1000 else if (it > 100_000_000_000L) it else null }
        is String -> v.toLongOrNull()?.let { toMillis(it) } ?: runCatching {
            java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli()
        }.recoverCatching { java.time.Instant.parse(v).toEpochMilli() }.getOrNull()
        else -> null
    }

    /** Durations: guess unit from key name / size. */
    private fun duration(o: JSONObject): Int? {
        for (k in TOTAL_KEYS) {
            val n = o.opt(k)?.let { if (it is Number) it.toDouble() else (it as? String)?.toDoubleOrNull() } ?: continue
            val lk = k.lowercase()
            val minutes = when {
                lk.endsWith("ms") -> n / 60000
                lk.contains("second") || lk == "seconds" -> n / 60
                n > 10_000_000 -> n / 60000   // looks like millis
                else -> n / 60                // assume seconds
            }
            return minutes.toInt()
        }
        return null
    }

    // ---------- SQLite ----------

    private fun parseSqlite(ctx: Context, bytes: ByteArray): Result {
        val f = File(ctx.cacheDir, "cocoon_import.db").apply { writeBytes(bytes) }
        val out = mutableListOf<PlayItem>()
        val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val tables = mutableListOf<String>()
            it.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> while (c.moveToNext()) tables += c.getString(0) }
            // Turn every table into JSON objects and reuse the JSON heuristics (fine for backup-sized DBs)
            val arr = JSONArray()
            for (t in tables) {
                if (t.startsWith("sqlite_") || t == "android_metadata") continue
                it.rawQuery("SELECT * FROM \"$t\" LIMIT 20000", null).use { c ->
                    while (c.moveToNext()) {
                        val o = JSONObject()
                        for (i in 0 until c.columnCount) {
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                                android.database.Cursor.FIELD_TYPE_STRING -> o.put(c.getColumnName(i), c.getString(i))
                            }
                        }
                        arr.put(o)
                    }
                }
            }
            out += parseJsonTree(arr)
            f.delete()
            return Result(merge(out), "Tables: ${tables.joinToString()}")
        }
    }

    // ---------- helpers ----------

    /** Combine records for the same game: sum per-day minutes, keep the largest total. */
    private fun merge(items: List<PlayItem>): List<PlayItem> =
        items.groupBy { it.title.lowercase() to it.platform.lowercase() }.values.map { group ->
            val days = HashMap<String, Int>()
            group.forEach { g -> g.days.forEach { (d, m) -> days[d] = (days[d] ?: 0) + m } }
            val total = group.mapNotNull { it.totalMinutes }.maxOrNull()
            val sum = days.values.sum()
            group.first().copy(
                days = days,
                totalMinutes = listOfNotNull(total, sum.takeIf { it > 0 }).maxOrNull(),
                lastPlayed = group.mapNotNull { it.lastPlayed }.maxOrNull(),
            )
        }.sortedByDescending { it.totalMinutes ?: 0 }

    /** Normalise common system names to the short labels Dotplay uses. */
    fun cleanPlatform(p: String): String {
        val s = p.lowercase()
        return when {
            s.isBlank() -> "Emulated"
            "ps2" in s || "playstation 2" in s -> "PS2"
            "psp" in s -> "PSP"
            "vita" in s -> "PS Vita"
            "psx" in s || "ps1" in s || s == "playstation" -> "PS1"
            "gamecube" in s || s == "gc" || "ngc" in s -> "GameCube"
            "wii u" in s || "wiiu" in s -> "Wii U"
            "wii" in s -> "Wii"
            "3ds" in s -> "3DS"
            "nds" in s || s == "ds" || "nintendo ds" in s -> "DS"
            "gba" in s || "advance" in s -> "GBA"
            "gbc" in s || "color" in s -> "GBC"
            "gb" == s || "game boy" in s -> "Game Boy"
            "n64" in s || "64" in s -> "N64"
            "snes" in s || "super nintendo" in s -> "SNES"
            "nes" in s || "famicom" in s -> "NES"
            "switch" in s -> "Switch"
            "dreamcast" in s -> "Dreamcast"
            "saturn" in s -> "Saturn"
            "genesis" in s || "mega drive" in s || "megadrive" in s -> "Genesis"
            else -> p.take(48)
        }
    }
}
