package app.dotplay.companion

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Uploads playtime (Cocoon games + tracked Android games).
 * Runs in the background at the interval you choose (default every 3 hours, only with network and when the battery
 * isn't low), once when the companion is opened, and from the Sync now button. Android batches these runs with other
 * apps' background work. When nothing changed since the last send, no network request is made at all.
 * Sending is idempotent (absolute minutes per day), so extra syncs never double count.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.configured) return Result.success()
        return try {
            sendIfChanged(applicationContext, prefs, force = false)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        /** Cocoon games (emulator time credited to the launched game) + tracked Android games. */
        fun collectAll(ctx: Context, prefs: Prefs): List<PlayItem> {
            val mappings = GameMappings.active(ctx)
            val cocoon = runCatching { CocoonLog.collect(ctx, prefs, skipPackages = mappings.keys) }.getOrDefault(emptyList())
            // On a Cocoon device, mapped apps use the "cocoon" source so they merge with imported Cocoon history
            val source = if (prefs.cocoonTree != null) "cocoon" else "android"
            val android = UsageTracker.collect(ctx, prefs.trackedApps, mappings = mappings, mappedSource = source)
            return cocoon + android
        }

        /**
         * Collect and send. Skips the network entirely when the data equals what was sent last time
         * (unless [force], e.g. the Sync now button). Returns a short status line.
         */
        fun sendIfChanged(ctx: Context, prefs: Prefs, force: Boolean): String {
            val items = collectAll(ctx, prefs)
            val hash = items.joinToString("|") { it.toJson().toString() }.hashCode().toString()
            val msg = when {
                items.isEmpty() -> "Nothing new to send"
                !force && hash == prefs.lastSentHash -> "Nothing changed since the last sync"
                else -> Api.sync(prefs, deviceName(), items).also { prefs.lastSentHash = hash }
            }
            prefs.lastSync = DateFormat.getDateTimeInstance().format(Date())
            return msg + items.joinToString("") { "\n· ${it.title} [${it.platform}]: ${it.days.values.sum()} min" }
        }

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private val background = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Background sync at the chosen interval; "only when opened" cancels it. */
        fun schedule(ctx: Context) {
            val every = Prefs(ctx).syncEveryMinutes
            val wm = WorkManager.getInstance(ctx)
            if (every <= 0) { wm.cancelUniqueWork("dotplay-sync"); return }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(every.toLong(), TimeUnit.MINUTES).setConstraints(background).build()
            wm.enqueueUniquePeriodicWork("dotplay-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        val INTERVALS = listOf(60 to "Every hour", 180 to "Every 3 hours (recommended)", 720 to "Twice a day", 0 to "Only when I open the app")
        fun intervalLabel(minutes: Int) = INTERVALS.firstOrNull { it.first == minutes }?.second?.substringBefore(" (") ?: "Every $minutes min"

        /** One sync as soon as there is network, e.g. when the companion opens. */
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("dotplay-sync-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
