package com.valhalla.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.valhalla.core.security.AuthToken
import com.valhalla.core.security.AuthTokenCodec
import com.valhalla.core.security.TokenStore
import com.valhalla.data.ble.AndroidBleSupport
import com.valhalla.data.settings.StorageKeys
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android 安全存储：令牌明文写入前用 Android Keystore 中的 AES-256/GCM 密钥加密，
 * 密文（IV || ciphertext）以 Base64 存进私有 SharedPreferences。
 *
 * 密钥本身不出 Keystore；应用卸载/清除数据后密钥失效，旧密文自然不可解密。
 * 任何解密异常都按「无令牌」处理并清除损坏数据，避免把异常抛到 UI 层。
 */
actual fun createTokenStore(): TokenStore = AndroidKeystoreTokenStore(AndroidBleSupport.requireContext())

private class AndroidKeystoreTokenStore(
    private val context: Context,
) : TokenStore {
    private val preferences =
        context.getSharedPreferences(
            StorageKeys.ANDROID_TOKEN_PREFS,
            Context.MODE_PRIVATE,
        )

    override fun save(token: AuthToken) {
        val plaintext = AuthTokenCodec.encode(token).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(plaintext)
        preferences
            .edit()
            .putString(StorageKeys.ANDROID_TOKEN_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    override fun load(): AuthToken? {
        val encoded = preferences.getString(StorageKeys.ANDROID_TOKEN_KEY, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size <= IV_SIZE) {
                clear()
                return null
            }
            val iv = blob.copyOfRange(0, IV_SIZE)
            val ciphertext = blob.copyOfRange(IV_SIZE, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            AuthTokenCodec.decode(plaintext.decodeToString()) ?: run {
                clear()
                null
            }
        } catch (_: Throwable) {
            // 密钥被系统回收、密文损坏或数据被迁移：统一视为无令牌。
            clear()
            null
        }
    }

    override fun clear() {
        preferences.edit().remove(StorageKeys.ANDROID_TOKEN_KEY).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getEntry(StorageKeys.ANDROID_TOKEN_KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    StorageKeys.ANDROID_TOKEN_KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
