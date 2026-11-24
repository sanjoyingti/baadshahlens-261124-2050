package com.aibuilt.baadshahlens.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenStore(context: Context) {

    private val file = File(context.filesDir, "token.bin")

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey(): SecretKey {
        val alias = "baadshah_token_key"
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(token: String) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(token.toByteArray())
            val combined = cipher.iv + encrypted
            file.writeBytes(combined)
        }
    }

    @Synchronized
    fun read(): String {
        if (!file.exists()) return ""
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < 13) return@runCatching ""
            val iv = bytes.copyOfRange(0, 12)
            val payload = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload))
        }.getOrDefault("")
    }

    fun clear() {
        runCatching { file.delete() }
    }

    fun masked(): String {
        val value = read()
        if (value.isEmpty()) return ""
        val tail = Base64.encodeToString(value.takeLast(4).toByteArray(), Base64.NO_WRAP)
        return "••••••••$tail"
    }
}
