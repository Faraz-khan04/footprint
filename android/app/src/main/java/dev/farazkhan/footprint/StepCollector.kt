package dev.farazkhan.footprint

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The step-counter sensor is a low-power chip that keeps counting in the background and reports
 * the total steps since the phone last booted. Each run we read that total and record the
 * difference from the previous reading. A smaller total than last time means the phone rebooted.
 */
class StepCollector(
    private val context: Context,
    private val cfg: Config,
    private val store: EventStore,
) {
    companion object {
        /** The 15-minute job and the app-rules service both read; one at a time, or a step counts twice. */
        private val lock = Mutex()

        /** Read only if the last reading is at least [minGapMs] old. For screen on/off in LockService. */
        suspend fun collectIfStale(context: Context, minGapMs: Long) {
            val cfg = Config(context)
            if (!cfg.disclosureAccepted) return
            val now = System.currentTimeMillis()
            if (now - cfg.lastStepAt < minGapMs) return
            StepCollector(context, cfg, EventStore.get(context)).collect(now)
        }

        fun hasPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        fun hasSensor(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }

    /** Returns steps recorded this run, or null if nothing could be read. */
    suspend fun collect(now: Long): Int? = lock.withLock { read(now) }

    private suspend fun read(now: Long): Int? {
        if (!cfg.collectSteps || !hasPermission(context)) return null
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null

        val total = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Float> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0])
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        } ?: return null

        val last = cfg.lastStepTotal
        val lastAt = cfg.lastStepAt
        if (now <= lastAt) return 0
        cfg.lastStepTotal = total
        cfg.lastStepAt = now
        if (last < 0 || lastAt == 0L) return 0             // first reading is only a baseline

        val delta = if (total >= last) total - last else total
        if (delta <= 0f) return 0
        store.steps(lastAt, now, delta.toDouble())
        return delta.toInt()
    }
}
