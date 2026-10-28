package com.btcusd.alerts.alarm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Manages custom ringtones for universal alerts and specific alert types (ENTRY, SL, TP, LEVEL, or custom).
 * Copies audio into app-internal storage so permissions NEVER expire and the system never
 * falls back to default alarm ringtone after app restarts or reboots.
 */
object SoundSettings {
    private const val PREFS = "btc_settings"

    const val KEY_UNIVERSAL = "universal"
    const val TYPE_ENTRY = "ENTRY"
    const val TYPE_SL = "SL"
    const val TYPE_TP = "TP"
    const val TYPE_LEVEL = "LEVEL"

    val PRESET_TYPES = listOf(TYPE_ENTRY, TYPE_SL, TYPE_TP, TYPE_LEVEL)

    private fun getStorageDir(ctx: Context): File {
        return File(ctx.filesDir, "custom_ringtones").apply { mkdirs() }
    }

    /**
     * Sanitizes a key to ensure a safe internal file name.
     */
    private fun sanitizeKey(key: String): String {
        return key.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
    }

    /**
     * Copies selected audio from external content/file URI directly into internal storage.
     * Returns the internal file URI if successful, or null on failure.
     */
    fun saveCustomAudio(ctx: Context, sourceUri: Uri, key: String): Uri? {
        return runCatching {
            val safeKey = sanitizeKey(key)
            val dir = getStorageDir(ctx)
            val destFile = File(dir, "$safeKey.audio")

            ctx.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            if (destFile.exists() && destFile.length() > 0) {
                val displayName = queryDisplayName(ctx, sourceUri) ?: "Custom Sound"
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                prefs.putString("${safeKey}_name", displayName)
                prefs.putString("${safeKey}_path", destFile.absolutePath)
                prefs.apply()
                Uri.fromFile(destFile)
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Returns the internal file Uri for a given sound key if it exists and is readable.
     */
    fun getSoundUri(ctx: Context, key: String): Uri? {
        val safeKey = sanitizeKey(key)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString("${safeKey}_path", null)
        if (path != null) {
            val file = File(path)
            if (file.exists() && file.length() > 0 && file.canRead()) {
                return Uri.fromFile(file)
            }
        }
        return null
    }

    /**
     * Returns human-readable label for the sound key (e.g., "Sad_Violin.mp3" or "Default alarm").
     */
    fun getSoundLabel(ctx: Context, key: String): String {
        val safeKey = sanitizeKey(key)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("${safeKey}_name", null)
        if (name != null) return name

        return when {
            safeKey == KEY_UNIVERSAL -> "Default alarm"
            safeKey.startsWith("type_") -> "Follows Universal"
            else -> "Default sound"
        }
    }

    /**
     * Clears custom sound for a specific key and deletes its internal file.
     */
    fun clearSound(ctx: Context, key: String) {
        val safeKey = sanitizeKey(key)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString("${safeKey}_path", null)
        if (path != null) {
            runCatching { File(path).delete() }
        }
        prefs.edit().remove("${safeKey}_name").remove("${safeKey}_path").apply()
    }

    /**
     * Key helper for an alert type: e.g. "type_sl", "type_tp"
     */
    fun keyForType(type: String): String = "type_${type.trim().uppercase()}"

    /**
     * Resolves the effective audio Uri for an alert:
     * 1. Per-alert specific ringtone (if explicitly set)
     * 2. Ringtone configured for this alertType (e.g. SL, TP, ENTRY, LEVEL)
     * 3. Universal custom ringtone
     * 4. null (fallback to system default alarm)
     */
    fun resolveEffectiveUri(ctx: Context, alertRingtoneUri: String?, alertType: String?): Uri? {
        // 1. Alert-specific custom tone
        if (!alertRingtoneUri.isNullOrBlank()) {
            val parsed = runCatching { Uri.parse(alertRingtoneUri) }.getOrNull()
            if (parsed != null && isAudioAccessible(ctx, parsed)) {
                return parsed
            }
        }

        // 2. Alert-type specific tone (e.g., SL, TP, ENTRY, LEVEL)
        if (!alertType.isNullOrBlank()) {
            val typeKey = keyForType(alertType)
            val typeUri = getSoundUri(ctx, typeKey)
            if (typeUri != null && isAudioAccessible(ctx, typeUri)) {
                return typeUri
            }
        }

        // 3. Universal tone
        val univUri = getSoundUri(ctx, KEY_UNIVERSAL)
        if (univUri != null && isAudioAccessible(ctx, univUri)) {
            return univUri
        }

        return null
    }

    fun isAudioAccessible(ctx: Context, uri: Uri): Boolean {
        return runCatching {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return false)
                f.exists() && f.length() > 0 && f.canRead()
            } else {
                ctx.contentResolver.openInputStream(uri)?.use { true } ?: false
            }
        }.getOrDefault(false)
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.take(36)
    }

    // Backward compatibility helpers
    fun getCustomUri(ctx: Context): Uri? = getSoundUri(ctx, KEY_UNIVERSAL)
    fun setCustomUri(ctx: Context, uri: Uri?) {
        if (uri == null) {
            clearSound(ctx, KEY_UNIVERSAL)
        } else {
            saveCustomAudio(ctx, uri, KEY_UNIVERSAL)
        }
    }
    fun label(ctx: Context): String = getSoundLabel(ctx, KEY_UNIVERSAL)
}
