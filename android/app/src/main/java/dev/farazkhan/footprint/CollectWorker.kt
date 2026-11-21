package dev.farazkhan.footprint

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** One pass: read new usage events, read the step counter, tidy up, refresh the widget. */
object Collector {
    private val lock = Mutex()

    suspend fun runOnce(context: Context): Int = lock.withLock {
        val ctx = context.applicationContext
        val cfg = Config(ctx)
        if (!cfg.disclosureAccepted) return@withLock 0
        val store = EventStore.get(ctx)
        val now = System.currentTimeMillis()
        var sessions = 0
        runCatching { sessions = UsageCollector(ctx, cfg, store).collect(now) }
        runCatching { StepCollector(ctx, cfg, store).collect(now) }
        runCatching { RecordedSteps.sync(ctx, cfg, store, now) }      // exact hourly steps, if Play services has them
        runCatching { store.prune(now, cfg.retentionDays) }
        cfg.lastCollectAt = now
        runCatching { FootprintWidget.refresh(ctx) }
        runCatching { Notify.runDue(ctx, now) }              // warnings, morning summary, weekly review
        runCatching { Notify.schedule(ctx, now) }            // and the alarm for the next one
        runCatching { Focus.finishIfDue(ctx, now) }       // in case the watcher was stopped by the system
        runCatching { RuleStore.applyDue(cfg, now); LockService.sync(ctx) }
        sessions
    }
}

/** Runs every 15 minutes, Android's minimum for a periodic job. */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Collector.runOnce(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CollectWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("footprint-collect", ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork("footprint-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CollectWorker>().build())
        }
    }
}
