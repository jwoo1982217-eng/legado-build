package io.legado.app.help.audiobook

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ProtectedAudiobookFile {

    const val EXTENSION = "jreadmp3"
    const val FORMAT = "protected_mp3"

    private const val KEY_ALIAS = "jread_protected_audiobook_mp3_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private val magic = byteArrayOf(0x4A, 0x52, 0x45, 0x41, 0x44, 0x4D, 0x50, 0x33)

    fun protectMp3(context: Context, sourceMp3: File, targetFile: File): File {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { output ->
            output.write(magic)
            output.write(1)
            output.write(cipher.iv.size)
            output.write(cipher.iv)
            sourceMp3.inputStream().use { input ->
                CipherOutputStream(output, cipher).use { cipherOutput ->
                    input.copyTo(cipherOutput)
                }
            }
        }
        sourceMp3.delete()
        return targetFile
    }

    fun openMp3InputStream(context: Context, file: File): InputStream {
        return ByteArrayInputStream(decryptMp3(context, file))
    }

    fun decryptMp3(context: Context, file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size > magic.size + 2) { "受保护音频文件无效" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "不是受保护有声书文件" }
        val version = bytes[magic.size].toInt() and 0xFF
        require(version == 1) { "不支持的受保护音频版本：$version" }
        val ivSize = bytes[magic.size + 1].toInt() and 0xFF
        val ivStart = magic.size + 2
        val ivEnd = ivStart + ivSize
        require(ivSize > 0 && ivEnd < bytes.size) { "受保护音频 IV 无效" }
        val iv = bytes.copyOfRange(ivStart, ivEnd)
        val encryptedBytes = bytes.copyOfRange(ivEnd, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encryptedBytes)
    }

    fun isProtectedFile(file: File): Boolean {
        return file.extension.equals(EXTENSION, ignoreCase = true)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
