package dev.farazkhan.footprint

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 over "<unix_ts>." + message, hex encoded. Same scheme as the server and laptop agent. */
object Signer {
    fun hmac(secret: String, ts: Long, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$ts.".toByteArray(Charsets.UTF_8))
        return mac.doFinal(message).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Read-only API calls for the dashboard. Each GET is signed with this phone's device secret
 * over "GET <path?query>", so the phone never needs the READ_TOKEN and the secret never
 * leaves native code.
 */
object Api {
    data class Response(val code: Int, val body: String, val error: String?)

    /** Only these paths can be requested from the dashboard. */
    private val ALLOWED = listOf("/v1/summary/", "/v1/icons", "/v1/apps/top")

    fun isAllowed(path: String): Boolean =
        ALLOWED.any { path.startsWith(it) } && !path.contains("..") && !path.contains("://")

    fun get(cfg: Config, path: String, retry: Boolean = true): Response {
        if (!cfg.isConfigured) return Response(0, "", "The app isn't set up yet. Open Settings")
        if (!isAllowed(path)) return Response(0, "", "Blocked request")
        val base = Discovery.find(cfg)
            ?: return Response(0, "", "Laptop not found. Is it connected to your hotspot and awake?")
        val ts = System.currentTimeMillis() / 1000
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("X-Device-Id", cfg.deviceId)
            setRequestProperty("X-Timestamp", ts.toString())
            setRequestProperty("X-Signature", Signer.hmac(cfg.secret, ts, "GET $path".toByteArray(Charsets.UTF_8)))
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val error = when (code) {
                in 200..299 -> null
                401, 403 -> "The server rejected this phone. Check the device ID and secret in Settings"
                else -> "Server answered $code"
            }
            Response(code, body, error)
        } catch (e: IOException) {
            Discovery.forget()                       // its address may have changed; look again once
            if (retry) get(cfg, path, retry = false)
            else Response(0, "", "Can't reach your laptop (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }
}
