package com.webtoapp.core.activation

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.webtoapp.core.logging.AppLogger
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DeviceIdGenerator {

    private const val TAG = "DeviceIdGenerator"
    private const val PREFS_NAME = "device_id_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_ID_HMAC = "device_id_hmac"
    private const val KEY_DEVICE_ID_PACKAGE = "device_id_package"
    private const val LEGACY_PACKAGE_MARKER = "<legacy-no-package>"
    private const val SECURE_PREFS_NAME = "device_id_secure_prefs"
    private const val KEY_FALLBACK_SECRET = "hmac_fallback_secret"

    private fun getHmacKeyMaterial(context: Context): ByteArray {
        return try {
            val sigBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (sigBytes != null && sigBytes.isNotEmpty()) {
                MessageDigest.getInstance("SHA-256").digest(sigBytes)
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read signing certificate; using fallback HMAC key", e)
            null
        } ?: getFallbackKeyMaterial(context)
    }

    private fun getFallbackKeyMaterial(context: Context): ByteArray {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val securePrefs = EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val existing = securePrefs.getString(KEY_FALLBACK_SECRET, null)
            val secret = if (!existing.isNullOrBlank()) {
                existing
            } else {
                val generated = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                securePrefs.edit().putString(KEY_FALLBACK_SECRET, generated).apply()
                generated
            }
            secret.toByteArray()
        } catch (e: Exception) {
            AppLogger.w(TAG, "EncryptedSharedPreferences unavailable; HMAC key quality degraded", e)
            "WTA_DeviceId_Fallback".toByteArray()
        }
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val packageName = runCatching { context.packageName }.getOrNull().orEmpty()
        return getDeviceId(context, packageName)
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context, packageName: String): String {
        val safePackage = packageName.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedId = prefs.getString(KEY_DEVICE_ID, null)
        val savedHmac = prefs.getString(KEY_DEVICE_ID_HMAC, null)
        val savedPackage = prefs.getString(KEY_DEVICE_ID_PACKAGE, null)

        if (!savedId.isNullOrBlank() && !savedHmac.isNullOrBlank()) {
            val keyMaterial = getHmacKeyMaterial(context)
            val expectedHmac = if (savedPackage == null) {
                computeLegacyHmac(savedId, keyMaterial)
            } else if (savedPackage == LEGACY_PACKAGE_MARKER) {
                computeLegacyHmac(savedId, keyMaterial)
            } else {
                computeHmac(savedId, savedPackage, keyMaterial)
            }
            if (constantTimeEquals(savedHmac, expectedHmac)) {

                if (savedPackage == null) {
                    prefs.edit()
                        .putString(KEY_DEVICE_ID_PACKAGE, LEGACY_PACKAGE_MARKER)
                        .apply()
                    AppLogger.i(TAG, "Legacy device ID preserved and marked for package=$safePackage")
                } else if (savedPackage == LEGACY_PACKAGE_MARKER) {
                    AppLogger.i(TAG, "Legacy device ID reused for package=$safePackage")
                }
                return savedId
            }
            AppLogger.w(TAG, "Device ID integrity check failed — regenerating")
        }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val deviceId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            computeDeviceId(androidId, safePackage)
        } else {
            computeDeviceId(UUID.randomUUID().toString().replace("-", ""), safePackage)
        }

        val hmac = computeHmac(deviceId, safePackage, getHmacKeyMaterial(context))
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_ID_HMAC, hmac)
            .putString(KEY_DEVICE_ID_PACKAGE, safePackage)
            .apply()

        AppLogger.i(TAG, "Device ID generated and persisted for package=$safePackage")
        return deviceId
    }

    fun computeDeviceId(androidId: String, packageName: String): String {
        val input = buildString {
            append(androidId)
            append('|')
            append(packageName.trim())
        }
        return hashString(input)
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun computeHmac(data: String, packageName: String, keyMaterial: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(keyMaterial, "HmacSHA256")
        mac.init(keySpec)
        val payload = "$data|$packageName".toByteArray()
        val hmacBytes = mac.doFinal(payload)
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    private fun computeLegacyHmac(data: String, keyMaterial: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(keyMaterial, "HmacSHA256")
        mac.init(keySpec)
        val hmacBytes = mac.doFinal(data.toByteArray())
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
