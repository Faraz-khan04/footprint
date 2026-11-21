package dev.farazkhan.footprint

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App settings plus collector bookkeeping, kept in private SharedPreferences. */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("footprint", Context.MODE_PRIVATE)

    init {
        // Older versions used a Tailscale address; the laptop is now found on the hotspot automatically.
        if (prefs.getString("api_url", "")?.contains(".ts.net") == true) {
            prefs.edit().remove("api_url").remove("laptop_url").apply()
        }
    }

    /** Optional manual laptop address (e.g. http://10.0.0.5:8000). Empty = find it automatically. */
    var apiUrl: String
        get() = prefs.getString("api_url", "") ?: ""
        set(v) = prefs.edit().putString("api_url", v.trim().trimEnd('/')).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(v) = prefs.edit().putString("device_id", v.trim()).apply()

    /** Stored encrypted with a key that never leaves the Android Keystore. */
    var secret: String
        get() = prefs.getString("secret_enc", null)?.let { SecretStore.decrypt(it) } ?: ""
        set(v) = prefs.edit().putString("secret_enc", SecretStore.encrypt(v.trim())).apply()

    val hasSecret: Boolean get() = prefs.contains("secret_enc")

    val isConfigured: Boolean
        get() = deviceId.isNotBlank() && secret.isNotBlank()

    /** Last address where the laptop answered the identity check. */
    var laptopUrl: String?
        get() = prefs.getString("laptop_url", null)
        set(v) = prefs.edit().putString("laptop_url", v).apply()

    // ---- usage collector state
    /** Usage events up to this time (epoch ms) have been processed. */
    var usageCursor: Long
        get() = prefs.getLong("usage_cursor", 0L)
        set(v) = prefs.edit().putLong("usage_cursor", v).apply()

    /** App still in the foreground when the last run ended, so the next run can continue it. */
    var carryPackage: String?
        get() = prefs.getString("carry_pkg", null)
        set(v) = prefs.edit().putString("carry_pkg", v).apply()

    var carrySince: Long
        get() = prefs.getLong("carry_since", 0L)
        set(v) = prefs.edit().putLong("carry_since", v).apply()

    var iconsSent: Set<String>
        get() = prefs.getStringSet("icons_sent", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("icons_sent", v).apply()

    // ---- step collector state
    /** Last raw step-counter reading (steps since boot); -1 means no baseline yet. */
    var lastStepTotal: Float
        get() = prefs.getFloat("step_total", -1f)
        set(v) = prefs.edit().putFloat("step_total", v).apply()

    var lastStepAt: Long
        get() = prefs.getLong("step_at", 0L)
        set(v) = prefs.edit().putLong("step_at", v).apply()

    // ---- sync status (shown on the setup screen)
    var lastSyncAt: Long
        get() = prefs.getLong("sync_at", 0L)
        set(v) = prefs.edit().putLong("sync_at", v).apply()

    var lastSyncMessage: String
        get() = prefs.getString("sync_msg", "Not synced yet") ?: ""
        set(v) = prefs.edit().putString("sync_msg", v).apply()
}

/** AES-256-GCM with a hardware-backed key where the phone supports it. */
object SecretStore {
    private const val ALIAS = "footprint_device_secret"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()
}
