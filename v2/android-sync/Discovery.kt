package dev.farazkhan.footprint

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Finds the laptop on the phone's own hotspot (or any local Wi-Fi the phone is on).
 *
 * 1. Try the manual address from Settings if you set one, otherwise the last address that worked.
 * 2. If that fails, scan the local /24 subnets of the phone's hotspot/Wi-Fi interfaces for port 8000.
 * 3. Only accept a host that proves it's your server: it must sign a random nonce with this
 *    phone's device secret (GET /v1/hello). A stranger on the hotspot can't pass this.
 */
object Discovery {
    const val PORT = 8000
    private const val VERIFY_TTL_MS = 5 * 60_000L

    @Volatile private var verifiedUrl: String? = null
    @Volatile private var verifiedAt = 0L
    private val random = SecureRandom()

    /** Base URL like http://10.23.4.17:8000, or null if the laptop can't be found right now. */
    @Synchronized
    fun find(cfg: Config): String? {
        val now = System.currentTimeMillis()
        verifiedUrl?.let { if (now - verifiedAt < VERIFY_TTL_MS) return it }

        val manual = cfg.apiUrl.trim().trimEnd('/')
        val candidates = if (manual.isNotEmpty()) listOf(manual) else listOfNotNull(cfg.laptopUrl)
        for (url in candidates) if (verify(cfg, url)) return remember(cfg, url)
        if (manual.isNotEmpty()) return null            // you asked for a specific address; don't scan

        return scan(cfg)?.let { remember(cfg, it) }
    }

    /** Call when a request to the laptop failed, so the next call looks again. */
    fun forget() {
        verifiedUrl = null
        verifiedAt = 0L
    }

    private fun remember(cfg: Config, url: String): String {
        verifiedUrl = url
        verifiedAt = System.currentTimeMillis()
        cfg.laptopUrl = url
        return url
    }

    /** Every other host address in the /24 of each local private IPv4 interface (hotspot, Wi-Fi). */
    fun candidateHosts(): List<String> {
        val skip = listOf("rmnet", "ccmni", "tun", "dummy", "v4-", "p2p", "ipsec", "lo")
        val out = LinkedHashSet<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (nif in interfaces) {
            val up = runCatching { nif.isUp && !nif.isLoopback && !nif.isVirtual }.getOrDefault(false)
            if (!up || skip.any { nif.name.lowercase().startsWith(it) }) continue
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                if (!addr.isSiteLocalAddress) continue
                val b = addr.address.map { it.toInt() and 0xff }
                val base = "${b[0]}.${b[1]}.${b[2]}."
                for (host in 1..254) if (host != b[3]) out += base + host
            }
        }
        return out.toList()
    }

    private fun portOpen(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, PORT), 350) }
        true
    } catch (e: IOException) {
        false
    }

    private fun scan(cfg: Config): String? {
        val hosts = candidateHosts()
        if (hosts.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(48)
        try {
            val open = pool.invokeAll(hosts.map { ip -> Callable { if (portOpen(ip)) ip else null } })
                .mapNotNull { runCatching { it.get() }.getOrNull() }
            return open.map { "http://$it:$PORT" }.firstOrNull { verify(cfg, it) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Mutual proof: we sign the request, the server must sign our nonce back with the same secret. */
    fun verify(cfg: Config, baseUrl: String): Boolean {
        val secret = cfg.secret
        if (secret.isBlank() || cfg.deviceId.isBlank()) return false
        val nonce = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val path = "/v1/hello?nonce=$nonce"
        val ts = System.currentTimeMillis() / 1000
        val conn = try {
            URL(baseUrl + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return false
        }
        return try {
            conn.connectTimeout = 2_000
            conn.readTimeout = 3_000
            conn.setRequestProperty("X-Device-Id", cfg.deviceId)
            conn.setRequestProperty("X-Timestamp", ts.toString())
            conn.setRequestProperty("X-Signature", Signer.hmac(secret, ts, "GET $path".toByteArray(Charsets.UTF_8)))
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val proof = JSONObject(body).optString("proof")
            val expected = Signer.hmac(secret, ts, "SERVER $nonce".toByteArray(Charsets.UTF_8))
            MessageDigest.isEqual(proof.toByteArray(), expected.toByteArray())
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
