package dev.farazkhan.footprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Limits, alarms and the step reading would stop after a restart, so they are started again here.
 * Also after an update: Android stops the app's service when a new version is installed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { LockService.sync(context) }
        runCatching { CollectWorker.schedule(context) }
        runCatching { Notify.schedule(context) }      // alarms don't survive a restart
    }
}
