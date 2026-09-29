package moe.shizuku.manager.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * aMiNo r1376: API keys are encrypted with an AES-256-GCM key that lives inside the
 * hardware-backed Android Keystore and never leaves the device. Ciphertext + IV are
 * stored in a private SharedPreferences file. The key material itself is NOT stored
 * in prefs, NOT logged, NOT included in any model request beyond the provider's own
 * auth header, and NOT embedded in the codebase.
 */
object SecureStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "amino_api_master"
    private const val PREFS = "amino_secure_store"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns Base64(iv + ciphertext), or null on any failure (never logs the input). */
    fun encrypt(context: Context, plain: CharArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(String(plain).toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.getOrNull()

    /** Returns the decrypted chars, or null on any failure. */
    fun decrypt(context: Context, encoded: String?): CharArray? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size <= GCM_IV_BYTES) return@runCatching null
            val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
            val ct = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8).toCharArray()
        }.getOrNull()
    }

    fun put(context: Context, name: String, plain: CharArray): Boolean {
        val enc = encrypt(context, plain) ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(name, enc).apply()
        return true
    }

    fun get(context: Context, name: String): CharArray? =
        decrypt(context, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null))

    fun has(context: Context, name: String): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null).isNullOrBlank()

    /** Removes the local encrypted copy (deleting the key deletes its only local copy). */
    fun clear(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(name).apply()
    }
}
