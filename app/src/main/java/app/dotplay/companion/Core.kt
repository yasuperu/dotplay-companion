package app.dotplay.companion

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Settings stored on the device. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("dotplay", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()

    var deviceKey: String
        get() = p.getString("key", "") ?: ""
        set(v) = p.edit().putString("key", v.trim()).apply()

    /** package name -> platform label (e.g. "Android") */
    var trackedApps: Set<String>
        get() = p.getStringSet("apps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("apps", v).apply()

    var lastSync: String
        get() = p.getString("lastSync", "never") ?: "never"
        set(v) = p.edit().putString("lastSync", v).putLong("lastSyncAt", System.currentTimeMillis()).apply()

    /** epoch millis of the last successful sync (0 = never) */
    val lastSyncAt: Long get() = p.getLong("lastSyncAt", 0L)

    /** Storage Access Framework tree URI of the Cocoon folder, for launch_debug.log */
    var cocoonTree: String?
        get() = p.getString("cocoonTree", null)
        set(v) = p.edit().putString("cocoonTree", v).apply()

    val configured get() = serverUrl.isNotBlank() && deviceKey.isNotBlank()
}

/** One game with playtime ready to send to the server. */
data class PlayItem(
    val title: String,
    val platformType: String,       // "mobile" | "emulated" | ...
    val platform: String,           // "Android", "PS2", ...
    val source: String,             // "android" | "cocoon"
    val days: Map<String, Int> = emptyMap(),   // yyyy-MM-dd -> minutes
    val totalMinutes: Int? = null,
    val lastPlayed: Long? = null,   // epoch millis
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("platformType", platformType)
        put("platform", platform)
        put("source", source)
        if (days.isNotEmpty()) put("days", JSONArray().apply {
            days.forEach { (d, m) -> put(JSONObject().put("day", d).put("minutes", m)) }
        })
        totalMinutes?.let { put("totalMinutes", it) }
        lastPlayed?.let { put("lastPlayed", isoUtc(it)) }
    }
}

fun dayKey(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

fun isoUtc(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

object Api {
    /** POST items to /companion/sync. Returns a short status message. Throws on failure. */
    fun sync(prefs: Prefs, device: String, items: List<PlayItem>): String {
        if (!prefs.configured) throw IllegalStateException("Set the server URL and device key first")
        val body = JSONObject()
            .put("device", device)
            .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            .toString()
        val conn = URL(prefs.serverUrl + "/companion/sync").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${prefs.deviceKey}")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP $code")
            throw RuntimeException(msg)
        }
        return "Synced ${items.size} games"
    }
}

/** Reads foreground time of chosen apps from Android's usage stats. */
object UsageTracker {
    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    data class AppEntry(val pkg: String, val label: String, val isGame: Boolean)

    fun launchableApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map {
                val game = Build.VERSION.SDK_INT >= 26 && it.category == ApplicationInfo.CATEGORY_GAME ||
                    (it.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), game)
            }
            .sortedWith(compareBy({ !it.isGame }, { it.label.lowercase() }))
    }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    } catch (e: Exception) { pkg }

    data class Span(val start: Long, val end: Long)

    /**
     * Foreground spans of the given packages over the last [days] days.
     * Android keeps detailed events for roughly a week, so we sync at least daily.
     */
    fun spans(ctx: Context, packages: Set<String>, days: Int = 7): Map<String, List<Span>> {
        if (packages.isEmpty() || !hasPermission(ctx)) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -days)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(start, end)
        val ev = UsageEvents.Event()
        val openSince = HashMap<String, Long>()
        val out = HashMap<String, MutableList<Span>>()

        fun close(pkg: String, at: Long) {
            val s = openSince.remove(pkg) ?: return
            if (at > s) out.getOrPut(pkg) { mutableListOf() } += Span(s, at)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg in packages && pkg !in openSince) openSince[pkg] = ev.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (pkg in packages) close(pkg, ev.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    openSince.keys.toList().forEach { close(it, ev.timeStamp) }
            }
        }
        openSince.keys.toList().forEach { close(it, end) }   // still playing right now
        return out
    }

    /** Minutes per day for each tracked Android game. */
    fun collect(ctx: Context, packages: Set<String>, days: Int = 7): List<PlayItem> =
        spans(ctx, packages, days).mapNotNull { (pkg, list) ->
            val byDay = HashMap<String, Long>()
            list.forEach { addSpan(byDay, it.start, it.end) }
            val minutes = byDay.mapValues { (it.value / 60000).toInt() }.filterValues { it > 0 }
            if (minutes.isEmpty()) null
            else PlayItem(label(ctx, pkg), "mobile", "Android", "android", minutes, lastPlayed = list.maxOf { it.end })
        }

    /** Split a session across midnight boundaries. */
    fun addSpan(byDay: HashMap<String, Long>, from: Long, to: Long) {
        var s = from
        while (s < to) {
            val midnight = Calendar.getInstance().apply {
                timeInMillis = s
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val e = minOf(to, midnight)
            val k = dayKey(s)
            byDay[k] = (byDay[k] ?: 0L) + (e - s)
            s = e
        }
    }
}
