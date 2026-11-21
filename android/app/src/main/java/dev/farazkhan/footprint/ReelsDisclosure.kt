package dev.farazkhan.footprint

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Google Play's prominent disclosure for the Accessibility service (Reels and Shorts blocking, "full"
 * edition only). It must appear in normal use of the app, not only inside Settings, and needs a clear
 * yes or no before the service is used. So it is shown:
 *  - once on the dashboard, as an offer, the first time Footprint opens after setup;
 *  - whenever the service is switched on in Android's settings without the user having agreed here
 *    (ReelsService does nothing until they agree);
 *  - from Settings > Reels and Shorts, before sending anyone to Android's Accessibility settings.
 */
object ReelsDisclosure {
    private const val K_ASKED = "reels_disclosure_shown"

    val MESSAGE = "Footprint can close YouTube Shorts, Instagram Reels and Snapchat Spotlight for you. " +
        "To do that it uses Android's Accessibility service (the AccessibilityService API).\n\n" +
        "What it can see: only while YouTube, Instagram or Snapchat is on screen, the names of the screen's " +
        "parts (for example \"reel player\") and which tab is selected. It does not read messages, captions, " +
        "comments or anything you type, and it ignores every other app.\n\n" +
        "What it does: when a short-video feed you chose to block opens, it presses Back once or twice.\n\n" +
        "What is kept: a count of how often it did that, on this phone. Nothing is collected, shared or sent " +
        "anywhere; Footprint has no internet permission.\n\n" +
        "You can turn it off at any time in Android's Accessibility settings."

    fun asked(cfg: Config): Boolean = cfg.ruleString(K_ASKED, "0") == "1"

    /**
     * The disclosure dialog. "I agree" records consent and opens Android's Accessibility settings
     * (unless the service is already on); "No thanks" records the refusal and, if the service is
     * already on, sends the user to switch it off.
     */
    fun show(activity: Activity, serviceOn: Boolean, onDone: () -> Unit = {}) {
        val cfg = Config(activity)
        cfg.setRuleString(K_ASKED, "1")
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("Allow Footprint to use Accessibility?")
            .setMessage(MESSAGE)
            .setCancelable(false)
            .setNegativeButton("No thanks") { _, _ ->
                RuleStore.setReelsConsent(cfg, false)
                if (serviceOn) {
                    openSettings(activity, "Turn off \"Footprint: Reels and Shorts\" here")
                } else toast(activity, "Nothing changed. Reels and Shorts blocking stays off.")
                onDone()
            }
            .setPositiveButton("I agree") { _, _ ->
                RuleStore.setReelsConsent(cfg, true)
                if (!serviceOn) openSettings(activity, "Find \"Footprint: Reels and Shorts\" and turn it on")
                onDone()
            }
            .show()
    }

    fun openSettings(activity: Activity, hint: String) {
        runCatching { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast(activity, "Open Settings > Accessibility > Installed apps > Footprint") }
        toast(activity, hint)
    }

    /** Is "Footprint: Reels and Shorts" switched on in Android's Accessibility settings? */
    fun serviceOn(activity: Activity): Boolean = runCatching {
        val pkg = activity.packageName
        val on = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        on.split(':').any { it.equals("$pkg/$pkg.ReelsService", ignoreCase = true) || it.equals("$pkg/.ReelsService", ignoreCase = true) }
    }.getOrDefault(false)

    private fun toast(activity: Activity, s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()
}
