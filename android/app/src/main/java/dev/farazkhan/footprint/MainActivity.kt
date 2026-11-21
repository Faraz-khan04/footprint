package dev.farazkhan.footprint

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The dashboard. The page is bundled in the app and loaded from a virtual origin
 * (https://appassets.androidplatform.net), so it works with no internet permission at all.
 *
 * The page asks this activity for data through `FootprintBridge`, which is only exposed to that
 * origin. Everything it can request is read from the phone's own database.
 */
class MainActivity : Activity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/assets/index.html"
        /** Open the dashboard on a given screen, e.g. "review" from the weekly notification. */
        const val EXTRA_SCREEN = "screen"
        private const val EXPORT_REQUEST = 41
        private const val DATA_DAYS = 35
    }

    private val ink = Color.parseColor("#151515")
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var web: WebView
    private var pendingExport: Pair<String, (Boolean) -> Unit>? = null

    private val bridge = WebViewCompat.WebMessageListener { _, message, _, isMainFrame, replyProxy ->
        if (!isMainFrame) return@WebMessageListener
        val msg = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@WebMessageListener
        handle(msg, replyProxy)
    }

    /** True when Android is not putting this app to sleep. Reading this needs no permission. */
    private fun isUnrestricted(): Boolean = runCatching {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)

    private fun reply(proxy: JavaScriptReplyProxy, id: String, status: Int, body: String, error: String? = null) {
        if (isDestroyed) return
        val out = JSONObject().put("id", id).put("status", status).put("body", body)
        if (error != null) out.put("error", error)
        runCatching { proxy.postMessage(out.toString()) }
    }

    /** Below this age the stored data is current enough to skip a read. */
    private val FRESH_MS = 20_000L

    private fun handle(msg: JSONObject, proxy: JavaScriptReplyProxy) {
        val id = msg.optString("id")
        val store = EventStore.get(this)
        when (msg.optString("op")) {
            "data" -> {
                val days = msg.optInt("days", DATA_DAYS).coerceIn(1, EventStore.KEEP_DAYS)
                val have = HashSet<String>()
                msg.optJSONArray("haveIcons")?.let { for (i in 0 until it.length()) have.add(it.optString(i)) }
                io.execute {
                    // Read anything that happened since the last pass first, so the dashboard is
                    // current the moment it opens instead of up to 15 minutes behind.
                    val cfg = Config(this)
                    if (System.currentTimeMillis() - cfg.lastCollectAt > FRESH_MS) {
                        runCatching { runBlocking { Collector.runOnce(applicationContext) } }
                    }
                    val json = runCatching { store.rawJson(System.currentTimeMillis() - days * 86_400_000L, have) }
                    runOnUiThread {
                        json.fold({ reply(proxy, id, 200, it) },
                            { reply(proxy, id, 500, "", "Could not read your data") })
                    }
                }
            }
            "status" -> io.execute {
                val cfg = Config(this)
                val body = JSONObject()
                    .put("ready", cfg.disclosureAccepted && UsageCollector.hasAccess(this))
                    .put("events", store.count())
                    .put("last_collect", cfg.lastCollectAt)
                    .put("usage", UsageCollector.hasAccess(this))
                    .put("steps", !StepCollector.hasSensor(this) || !cfg.collectSteps || StepCollector.hasPermission(this))
                    .put("nudges", !cfg.nudgesEnabled || Nudges.canPost(this))
                    .put("battery_unrestricted", isUnrestricted())
                    .toString()
                runOnUiThread { reply(proxy, id, 200, body) }
            }
            "collect" -> io.execute {
                runCatching { runBlocking { Collector.runOnce(applicationContext) } }
                runOnUiThread { reply(proxy, id, 200, "{}") }
            }
            "batterySettings" -> {
                // Open the battery settings screen. We never ask for an exemption, which would
                // need a permission Play scrutinises; the user decides there themselves.
                // Footprint's own App info page first: Battery is one tap from there.
                val opened = runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                    true
                }.getOrElse {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        true
                    }.getOrDefault(false)
                }
                if (opened) android.widget.Toast.makeText(this,
                    if (android.os.Build.VERSION.SDK_INT >= 34) "Open Battery, tap the words \u201cAllow background usage\u201d (not the switch), then choose Unrestricted."
                    else "Open Battery, then choose Unrestricted.", android.widget.Toast.LENGTH_LONG).show()
                reply(proxy, id, if (opened) 200 else 500, "{}", if (opened) null else "Could not open battery settings")
            }
            "settings" -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                reply(proxy, id, 200, "{}")
            }
            "share" -> {
                val png = msg.optString("png")
                io.execute {
                    val ok = runCatching { shareReceipt(png) }.getOrDefault(false)
                    runOnUiThread { reply(proxy, id, if (ok) 200 else 500, "{}", if (ok) null else "Could not create the image") }
                }
            }
            "export" -> {
                val format = if (msg.optString("format") == "csv") "csv" else "json"
                pendingExport = format to { ok ->
                    reply(proxy, id, if (ok) 200 else 500, "{}", if (ok) null else "Export cancelled")
                }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (format == "csv") "text/csv" else "application/json"
                        putExtra(Intent.EXTRA_TITLE, "footprint-$stamp.$format")
                    }, EXPORT_REQUEST
                )
            }
            "wipe" -> AlertDialog.Builder(this)
                .setTitle("Delete everything?")
                .setMessage("Every event, app icon and setting stored by Footprint on this phone will be deleted. This can't be undone.")
                .setNegativeButton("Cancel") { _, _ -> reply(proxy, id, 200, JSONObject().put("wiped", false).toString()) }
                .setPositiveButton("Delete") { _, _ ->
                    io.execute {
                        runCatching { store.wipe(); Config(this).resetCollection(); Motivation.wipe(Config(this)); RuleStore.resetCounters(Config(this)); FootprintWidget.refresh(this) }
                        runOnUiThread {
                            reply(proxy, id, 200, JSONObject().put("wiped", true).toString())
                            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setOnCancelListener { reply(proxy, id, 200, JSONObject().put("wiped", false).toString()) }
                .show()
            "labels" -> {
                io.execute {
                    val json = runCatching { store.labels().toString() }
                    runOnUiThread {
                        json.fold({ reply(proxy, id, 200, it) },
                            { reply(proxy, id, 500, "{}", it.message ?: "could not read answers") })
                    }
                }
            }
            "setLabel" -> {
                val key = msg.optString("key")
                if (key.isEmpty()) reply(proxy, id, 400, "{}", "no key")
                else {
                    store.setLabel(key, msg.optBoolean("regret"), msg.optString("pkg"), msg.optLong("start"))
                    FootprintWidget.refresh(this)
                    reply(proxy, id, 200, JSONObject().put("count", store.labelCount()).toString())
                }
            }
            "delLabel" -> {
                store.deleteLabel(msg.optString("key"))
                FootprintWidget.refresh(this)
                reply(proxy, id, 200, JSONObject().put("count", store.labelCount()).toString())
            }
            "importLabels" -> {
                // One-off: answers given before they were stored here are handed over by the dashboard.
                val obj = msg.optJSONObject("labels") ?: JSONObject()
                var n = 0
                for (k in obj.keys()) {
                    if (store.isLabelled(k)) continue
                    store.setLabel(k, obj.optInt(k) == 1)
                    n++
                }
                FootprintWidget.refresh(this)
                reply(proxy, id, 200, JSONObject().put("imported", n).toString())
            }
            "focus" -> io.execute {
                val body = runCatching { Focus.state(this).toString() }.getOrDefault("{}")
                runOnUiThread { reply(proxy, id, 200, body) }
            }
            "focusStart" -> io.execute {
                runCatching {
                    Focus.start(this, msg.optString("subject"), msg.optInt("minutes", 25).coerceIn(0, 240),
                        msg.optBoolean("lock", true))
                }
                val body = runCatching { Focus.state(this).toString() }.getOrDefault("{}")
                runOnUiThread { reply(proxy, id, 200, body) }
            }
            "focusStop" -> io.execute {
                val minutes = runCatching { Focus.stop(this) }.getOrDefault(0)
                val body = runCatching { Focus.state(this).put("recorded", minutes).toString() }.getOrDefault("{}")
                runOnUiThread { reply(proxy, id, 200, body) }
            }
            // ---- Future You: your goal, notes to yourself, and what shows on the lock screens
            "future" -> io.execute {
                val body = runCatching { Motivation.state(this).toString() }.getOrDefault("{}")
                runOnUiThread { reply(proxy, id, 200, body) }
            }
            "futureSet" -> io.execute {
                val cfg = Config(this)
                runCatching {
                    if (msg.has("goal")) Motivation.setGoal(cfg, msg.optJSONObject("goal"))
                    if (msg.has("step")) Motivation.stepGoal(cfg, msg.optInt("step").coerceIn(-1, 1))
                    msg.optJSONObject("options")?.let { Motivation.setOptions(cfg, it) }
                    if (msg.has("note")) Motivation.addNote(cfg, msg.optString("note"), msg.optString("when", "ANY"))
                    if (msg.has("delNote")) Motivation.deleteNote(cfg, msg.optLong("delNote"))
                    if (msg.optBoolean("dismissAsk")) Motivation.dismissAsk(cfg)
                }
                val body = runCatching { Motivation.state(this).toString() }.getOrDefault("{}")
                runOnUiThread { reply(proxy, id, 200, body) }
            }
            "setPref" -> {
                // Only the settings the dashboard is allowed to change; everything else lives in Settings.
                val cfg = Config(this)
                when (msg.optString("key")) {
                    "stepsGoal" -> cfg.stepsGoal = msg.optInt("value").coerceIn(1_000, 30_000)
                    else -> { reply(proxy, id, 400, "{}", "not a setting the dashboard can change"); return }
                }
                FootprintWidget.refresh(this)
                reply(proxy, id, 200, cfg.toJson().toString())
            }
            "prefs" -> {
                val cfg = Config(this)
                // The dialer is reported so the dashboard never asks whether a phone call was worth it.
                val dialer = runCatching {
                    (getSystemService(android.telecom.TelecomManager::class.java))?.defaultDialerPackage
                }.getOrNull().orEmpty()
                reply(proxy, id, 200, cfg.toJson()
                    .put("canPost", Nudges.canPost(this))
                    .put("dialer", dialer)
                    .toString())
            }
            "nudges" -> {
                runCatching { Nudges.save(this, msg.optJSONArray("list") ?: org.json.JSONArray()) }
                reply(proxy, id, 200, JSONObject().put("canPost", Nudges.canPost(this)).toString())
            }
            "notifSettings" -> {
                val pkg = msg.optString("pkg")
                val opened = runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg))
                    true
                }.getOrElse {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("package:$pkg")).setAction(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")))
                        true
                    }.getOrDefault(false)
                }
                reply(proxy, id, if (opened) 200 else 500, "{}", if (opened) null else "Could not open settings")
            }
            else -> reply(proxy, id, 400, "", "Unknown request")
        }
    }

    /** Writes the receipt PNG into the app's cache and hands it to the share sheet. */
    private fun shareReceipt(base64Png: String): Boolean {
        val bytes = Base64.decode(base64Png, Base64.DEFAULT)
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "footprint-week.png")
        file.writeBytes(bytes)
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runOnUiThread { startActivity(Intent.createChooser(send, "Share your week")) }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_REQUEST) return
        val (format, done) = pendingExport ?: return
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { done(false); return }
        io.execute {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        if (format == "csv") EventStore.get(this).exportCsv(writer) else EventStore.get(this).exportJson(writer)
                    }
                } ?: error("no stream")
                true
            }.getOrDefault(false)
            runOnUiThread {
                done(ok)
                Toast.makeText(this, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assets = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(ink)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true        // theme choice and exam-mode settings
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == HOST) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "FootprintBridge", setOf("https://$HOST"), bridge)
        } else {
            Toast.makeText(this, "Update Android System WebView from the Play Store to see your data", Toast.LENGTH_LONG).show()
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ink)
            fitsSystemWindows = true
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        val screen = intent?.getStringExtra(EXTRA_SCREEN)?.takeIf { it.matches(Regex("[a-z]{1,12}")) }
        web.loadUrl(if (screen != null) "$START_URL#$screen" else START_URL)

        val cfg = Config(this)
        if (cfg.disclosureAccepted && UsageCollector.hasAccess(this)) {
            CollectWorker.schedule(this)
        } else if (savedInstanceState == null) {
            // only on a fresh start, so rotating or returning never bounces you back to Setup
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_ONBOARDING, true))
        }
    }

    override fun onResume() {
        super.onResume()
        LockService.sync(this)
        web.onResume()
        web.evaluateJavascript("window.fpRefresh && window.fpRefresh()", null)
        askAboutReels()
    }

    private var reelsAsking = false

    /**
     * Play requires the Accessibility disclosure in normal use, not only in Settings: offer it once on
     * the dashboard after setup, and again whenever the service is on without the user's agreement.
     */
    private fun askAboutReels() {
        if (!BuildConfig.REELS || reelsAsking) return
        val cfg = Config(this)
        if (!cfg.disclosureAccepted || !UsageCollector.hasAccess(this)) return
        val on = ReelsDisclosure.serviceOn(this)
        val needed = !RuleStore.reelsConsent(cfg) && (on || !ReelsDisclosure.asked(cfg))
        if (!needed) return
        reelsAsking = true
        ReelsDisclosure.show(this, on) { reelsAsking = false }
    }

    /** Back goes to the previous tab first (the dashboard pushes one history entry per tab), then leaves. */
    @Deprecated("Fine for a plain Activity; predictive back isn't used here")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val screen = intent.getStringExtra(EXTRA_SCREEN)?.takeIf { it.matches(Regex("[a-z]{1,12}")) } ?: return
        web.evaluateJavascript("window.fpShow && window.fpShow('$screen')", null)
    }

    override fun onPause() { web.onPause(); super.onPause() }

    override fun onDestroy() { io.shutdownNow(); web.destroy(); super.onDestroy() }
}
