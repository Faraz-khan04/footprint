package dev.farazkhan.footprint

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Sends queued events in signed batches. Same signing scheme as the laptop agent. */
class Uploader(private val cfg: Config, private val queue: EventQueue) {

    /** Uploads until the queue is empty or something fails. Returns a status line for the UI. */
    fun uploadAll(): String {
        var sent = 0
        val secret = cfg.secret
        if (queue.size() == 0L) return "Up to date"
        val base = Discovery.find(cfg)
            ?: return "Laptop not found on your hotspot. ${queue.size()} events waiting; they'll send when it's back."
        repeat(40) {
            val rows = queue.peek(500)
            if (rows.isEmpty()) return if (sent == 0) "Up to date" else "Synced $sent events"

            val events = JSONArray().apply { rows.forEach { put(JSONObject(it.second)) } }
            val body = JSONObject().put("events", events).toString().toByteArray(Charsets.UTF_8)
            val ts = System.currentTimeMillis() / 1000

            val conn = (URL("$base/v1/events").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-Id", cfg.deviceId)
                setRequestProperty("X-Timestamp", ts.toString())
                setRequestProperty("X-Signature", Signer.hmac(secret, ts, body))
            }
            val code = try {
                conn.outputStream.use { it.write(body) }
                conn.responseCode
            } catch (e: IOException) {
                Discovery.forget()
                return "Lost the laptop mid-sync (${e.javaClass.simpleName}). ${queue.size()} events waiting."
            } finally {
                conn.disconnect()
            }

            when (code) {
                200 -> {
                    queue.drop(rows.map { it.first })
                    sent += rows.size
                }
                401, 403 -> return "Server rejected this device. Check the device ID, secret and the phone's clock."
                422 -> {
                    queue.drop(rows.map { it.first })
                    return "Server rejected a batch as invalid, so it was discarded. Update the app and server."
                }
                else -> return "Server error $code. Will retry next sync."
            }
        }
        return "Synced $sent events, more waiting"
    }
}

/** Runs every 15 minutes (Android's minimum): collect usage, collect steps, upload. */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = lock.withLock {
        val ctx = applicationContext
        val cfg = Config(ctx)
        if (!cfg.isConfigured) return@withLock Result.success()

        val queue = EventQueue(ctx)
        try {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching { UsageCollector(ctx, cfg, queue).collect(now) }
            }
            runCatching { StepCollector(ctx, cfg, queue).collect(now) }
            val message = withContext(Dispatchers.IO) {
                runCatching { Uploader(cfg, queue).uploadAll() }.getOrElse { "Sync failed: ${it.message}" }
            }
            cfg.lastSyncAt = now
            cfg.lastSyncMessage = message
        } finally {
            queue.close()
        }
        Result.success()
    }

    companion object {
        private val lock = Mutex()   // periodic and "sync now" runs never overlap

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
