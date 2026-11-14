package com.deskpad.app.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is written; the wrapping key remains in Android Keystore. */
class PairingStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "deskpad-pairings-v2"))
    private val lock = Any()
    private val random = SecureRandom()

    fun load(endpoint: String): PairingCode? = synchronized(lock) {
        val entries = readEntries()
        val encrypted = entries[endpoint] ?: return@synchronized null
        try {
            val bytes = Base64.getUrlDecoder().decode(encrypted)
            require(bytes.size >= 12 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(endpoint.toByteArray(StandardCharsets.UTF_8))
            PairingCode.parse(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8))
        } catch (_: Exception) {
            // A restore, key reset or corrupt record must cause re-pairing, never weaker storage.
            entries.remove(endpoint)
            runCatching { writeEntries(entries) }
            null
        }
    }

    fun save(endpoint: String, pairing: PairingCode) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), random)
        cipher.updateAAD(endpoint.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.iv + cipher.doFinal(pairing.encoded().toByteArray(StandardCharsets.UTF_8))
        val entries = readEntries()
        entries[endpoint] = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
        writeEntries(entries)
    }

    fun forget(endpoint: String) = synchronized(lock) {
        val entries = readEntries()
        entries.remove(endpoint)
        writeEntries(entries)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun readEntries(): MutableMap<String, String> {
        if (!file.baseFile.exists()) return linkedMapOf()
        // Bound storage and reject malformed records instead of trusting arbitrary restored data.
        if (file.baseFile.length() > 65536) return linkedMapOf()
        return runCatching {
            file.openRead().bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size == 2 && parts[0].length <= 512 && parts[1].length <= 1024) {
                        parts[0] to parts[1]
                    } else null
                }.take(64).toMap(linkedMapOf())
            }
        }.getOrDefault(linkedMapOf())
    }

    private fun writeEntries(entries: Map<String, String>) {
        require(entries.size <= 64) { "Too many saved PCs. Forget one before pairing another." }
        val stream = file.startWrite()
        try {
            stream.write(entries.entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray(StandardCharsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    companion object {
        private const val KEY_ALIAS = "deskpad.pairings.v2.aes"
        fun endpoint(host: String, port: Int): String {
            val clean = host.trim().lowercase()
            require(clean.isNotEmpty() && clean.length <= 253 && clean.none { it.isWhitespace() || it.code < 32 }) {
                "Enter a valid PC address"
            }
            require(port in 1..65535) { "Port must be between 1 and 65535" }
            return "$clean:$port"
        }
    }
}
