package dev.farazkhan.footprint

/**
 * The alarm going off is not screen time. The clock app's alarm screen shows over the lock screen,
 * and if it rings and gets snoozed for an hour it would otherwise count as an hour on the phone.
 *
 * Rule: the clock app only counts once the phone has been unlocked since the screen last went off.
 * (A phone with no lock at all never reports an unlock, so there the clock app never counts.)
 * Every other app is untouched. Pure Kotlin, tested on the JVM (tools/test/AlarmGateTest.kt).
 */
class AlarmGate(private val clockApps: Set<String>, unlocked: Boolean = true) {

    /** Unlocked since the screen last came on. Saved between runs (Config.carryUnlocked). */
    var unlocked: Boolean = unlocked
        private set

    /** A clock app that came to the front while locked; it starts counting if you then unlock. */
    var held: String? = null
        private set

    /** The phone locks when the screen goes off (events can arrive either side of the alarm's own). */
    fun screenOff() { unlocked = false; held = null }
    fun screenOn() { unlocked = false }

    /** Returns the clock app that was waiting, if any: it is now in use and starts counting. */
    fun unlock(): String? { unlocked = true; return held.also { held = null } }

    /** Should this app's time count from now? A clock app on the lock screen is held, not counted. */
    fun counts(pkg: String): Boolean {
        if (pkg in clockApps && !unlocked) { held = pkg; return false }
        held = null
        return true
    }

    companion object {
        /** Clock apps on common phones; the phone's own alarm app is added at run time. */
        val KNOWN = setOf(
            "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock", "com.coloros.alarmclock", "com.oplus.alarmclock", "com.android.BBKClock",
            "com.miui.clock", "com.huawei.android.clock", "com.nothing.clock", "com.motorola.timeweatherwidget",
        )
    }
}
