package dev.notipush

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The AES key stays in Android Keystore; ciphertext is useless on another install. */
class Vault {
    private val alias = "notipush.local.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28) { "Invalid encrypted data" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
    @Synchronized fun eraseKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}

class SecureSettings(context: Context, private val vault: Vault) {
    val preferences = context.getSharedPreferences("notipush", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) {
            preferences.edit().putBoolean("enabled", value)
                .apply { if (value) putLong("enabledSince", System.currentTimeMillis()) }.commit()
        }
    val enabledSince get() = preferences.getLong("enabledSince", System.currentTimeMillis())
    var includeOngoing: Boolean
        get() = preferences.getBoolean("ongoing", false)
        set(value) { preferences.edit().putBoolean("ongoing", value).apply() }
    var lastListenerError: String
        get() = preferences.getString("listenerError", "") ?: ""
        set(value) { preferences.edit().putString("listenerError", value).apply() }
    fun destination(): Destination {
        val encrypted = preferences.getString("destination", null) ?: return Destination()
        val data = JSONObject(vault.decrypt(encrypted))
        return Destination(data.getString("token"), data.getString("user"), data.optString("device"), data.optInt("priority"))
    }
    fun saveDestination(destination: Destination) {
        require(destination.validationError() == null)
        val data = JSONObject().put("token", destination.token).put("user", destination.user)
            .put("device", destination.device).put("priority", destination.priority)
        check(preferences.edit().putString("destination", vault.encrypt(data.toString())).commit())
    }
    fun rules(): Map<String, AppRule> {
        val data = JSONObject(preferences.getString("rules", "{}") ?: "{}")
        return data.keys().asSequence().associateWith { pkg ->
            val rule = data.getJSONObject(pkg)
            AppRule(rule.optBoolean("enabled", false), rule.optString("include"), rule.optString("exclude"), rule.optLong("since"))
        }
    }
    @Synchronized fun saveRule(packageName: String, rule: AppRule) {
        val data = JSONObject(preferences.getString("rules", "{}") ?: "{}")
        data.put(packageName, JSONObject().put("enabled", rule.enabled).put("include", rule.include)
            .put("exclude", rule.exclude).put("since", rule.since))
        preferences.edit().putString("rules", data.toString()).commit()
    }
    fun initialize() {
        if (!preferences.contains("initialized")) {
            starterApps.take(3).forEach { saveRule(it.packageName, AppRule()) }
            preferences.edit().putBoolean("initialized", true).commit()
        }
    }
    fun erase() { preferences.edit().clear().commit(); initialize() }
}
