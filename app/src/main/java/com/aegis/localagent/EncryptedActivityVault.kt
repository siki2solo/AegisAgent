package com.aegis.localagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ActivityRecord(
    val id: String,
    val timestampIso: String,
    val command: String,
    val targetPackage: String,
    val status: String,
    val skippedProtectedCount: Int
)

class EncryptedActivityVault(private val context: Context) {

    private val alias = "AegisVaultAesKey"
    private val storeFile = File(context.filesDir, "aegis_activity_vault.enc")

    private fun obtainSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun appendRecord(record: ActivityRecord) {
        val current = readAllRecords().toMutableList()
        current.add(0, record)
        writeAllRecords(current.take(250))
    }

    fun readAllRecords(): List<ActivityRecord> {
        if (!storeFile.exists()) return emptyList()
        return try {
            val wrapper = JSONObject(storeFile.readText(StandardCharsets.UTF_8))
            val iv = Base64.decode(wrapper.getString("iv"), Base64.NO_WRAP)
            val payload = Base64.decode(wrapper.getString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, obtainSecretKey(), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(payload), StandardCharsets.UTF_8)
            val arr = JSONArray(plain)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        ActivityRecord(
                            id = obj.getString("id"),
                            timestampIso = obj.getString("timestampIso"),
                            command = obj.getString("command"),
                            targetPackage = obj.getString("targetPackage"),
                            status = obj.getString("status"),
                            skippedProtectedCount = obj.optInt("skippedProtectedCount", 0)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun wipeVault() {
        if (storeFile.exists()) {
            storeFile.delete()
        }
    }

    private fun writeAllRecords(records: List<ActivityRecord>) {
        val arr = JSONArray()
        records.forEach { rec ->
            arr.put(
                JSONObject().apply {
                    put("id", rec.id)
                    put("timestampIso", rec.timestampIso)
                    put("command", rec.command)
                    put("targetPackage", rec.targetPackage)
                    put("status", rec.status)
                    put("skippedProtectedCount", rec.skippedProtectedCount)
                }
            )
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, obtainSecretKey())
        val encrypted = cipher.doFinal(arr.toString().toByteArray(StandardCharsets.UTF_8))
        val envelope = JSONObject().apply {
            put("alg", "AES-256-GCM")
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        storeFile.writeText(envelope.toString(), StandardCharsets.UTF_8)
    }
}
