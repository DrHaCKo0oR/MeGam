package app.shaqati.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted with a key that never leaves the phone's Android Keystore.
 * They are only ever sent to the service they belong to.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Changes whenever a key is set or cleared. */
    val version: StateFlow<Int> = _version

    fun get(name: String): String? = prefs.getString(name, null)?.let(::decrypt)?.takeIf { it.isNotBlank() }

    fun has(name: String): Boolean = get(name) != null

    fun set(name: String, value: String?) {
        val clean = value?.trim().orEmpty()
        prefs.edit().apply {
            if (clean.isEmpty()) remove(name) else putString(name, encrypt(clean))
        }.apply()
        _version.value = _version.value + 1
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all, 0, IV_BYTES))
        String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val ANTHROPIC = "anthropic"
        const val GOOGLE = "google"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "shaqati_vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
