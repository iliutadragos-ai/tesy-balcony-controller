package ro.iliuta.tesybalcony

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TesyCredentials(val email: String, val password: String, val userId: String)

/** AES-GCM encryption with a non-exportable key held by Android Keystore. */
class SecureCredentials(private val prefs: android.content.SharedPreferences) {
    private val alias = "tesy_balcony_credentials_v1"

    fun save(credentials: TesyCredentials) {
        val json = JSONObject()
            .put("email", credentials.email)
            .put("password", credentials.password)
            .put("userId", credentials.userId)
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        prefs.edit().putString("credentials", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun load(): TesyCredentials? {
        val encoded = prefs.getString("credentials", null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > 12) { "Stored credentials are incomplete" }
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            TesyCredentials(json.getString("email"), json.getString("password"), json.optString("userId", ""))
        } catch (ignored: Exception) {
            // Do not display or log ciphertext, secrets, or keystore errors.
            prefs.edit().remove("credentials").apply()
            null
        }
    }

    fun clear() { prefs.edit().remove("credentials").apply() }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
