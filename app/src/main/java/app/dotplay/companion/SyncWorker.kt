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
 * Runs every hour in the background, once when the companion is opened, and from the Sync now button.
 * Sending is idempotent (absolute minutes per day), so extra syncs never double count.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.configured) return Result.success()
        return try {
            val items = collectAll(applicationContext, prefs)
            if (items.isNotEmpty()) Api.sync(prefs, deviceName(), items)
            prefs.lastSync = DateFormat.getDateTimeInstance().format(Date())
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

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Hourly background sync (UPDATE replaces an older, slower schedule after an app update). */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("dotplay-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** One sync as soon as there is network, e.g. when the companion opens. */
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("dotplay-sync-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
