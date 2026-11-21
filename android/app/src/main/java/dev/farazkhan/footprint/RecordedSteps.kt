package dev.farazkhan.footprint

import android.content.Context
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Steps from Google Play services' on-device step recording (the "Recording API on mobile").
 *
 * Android only lets an app read the step sensor while it is on screen, so Footprint's own readings
 * can span a whole day. Play services keeps counting in the background and stores hourly totals on
 * the phone for 10 days; we ask for them whenever Footprint runs. No Google account, no internet,
 * nothing leaves the phone. Data only exists from the moment we first subscribe.
 */
object RecordedSteps {
    private const val HOUR = 3_600_000L
    /** Play services keeps 10 days; ask for a little less. */
    private const val MAX_BACK_MS = 9L * 24 * HOUR
    /** Re-read the last hours each time: the current hour is still filling up. */
    private const val OVERLAP_MS = 2 * HOUR
    private const val TIMEOUT_S = 20L

    fun available(context: Context): Boolean = runCatching {
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(
            context, LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** Subscribe once, then copy new hourly totals into the database. Never on the main thread. */
    fun sync(context: Context, cfg: Config, store: EventStore, now: Long = System.currentTimeMillis()) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        if (!cfg.collectSteps || !StepCollector.hasPermission(context)) return
        if (!available(context)) { cfg.recordingStatus = "Google Play services is missing or too old"; return }
        try {
            val client = FitnessLocal.getLocalRecordingClient(context)
            if (cfg.recordingSince == 0L) {
                Tasks.await(client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA), TIMEOUT_S, TimeUnit.SECONDS)
                cfg.recordingSince = now
            }
            val from = maxOf(cfg.recordingSince, now - MAX_BACK_MS, cfg.recordingSyncedTo - OVERLAP_MS) / HOUR * HOUR
            if (now - from < 60_000L) return
            val request = LocalDataReadRequest.Builder()
                .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
                .bucketByTime(1, TimeUnit.HOURS)
                .setTimeRange(from, now, TimeUnit.MILLISECONDS)
                .build()
            val response = Tasks.await(client.readData(request), TIMEOUT_S, TimeUnit.SECONDS)
            var hours = 0
            for (bucket in response.buckets) for (set in bucket.dataSets) for (point in set.dataPoints) {
                val steps = point.getValue(LocalField.FIELD_STEPS).asInt()
                val s = point.getStartTime(TimeUnit.MILLISECONDS); val e = point.getEndTime(TimeUnit.MILLISECONDS)
                if (steps > 0 && e > s) { store.recordedSteps(s, e, steps.toDouble()); hours++ }
            }
            cfg.recordingSyncedTo = now
            cfg.recordingStatus = "ok"
        } catch (t: Throwable) {
            cfg.recordingStatus = (t.cause ?: t).let { it.javaClass.simpleName + (it.message?.let { m -> ": " + m.take(80) } ?: "") }
        }
    }
}
