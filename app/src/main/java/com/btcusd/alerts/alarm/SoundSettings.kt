package com.btcusd.alerts.alarm

import android.content.Context
import android.net.Uri

/** Global alert-sound setting: default system alarm or user-picked audio file. */
object SoundSettings {
    private const val PREFS = "btc_settings"
    private const val KEY_URI = "custom_ringtone_uri"

    fun getCustomUri(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, null)
        return s?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    fun setCustomUri(ctx: Context, uri: Uri?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (uri == null) remove(KEY_URI) else putString(KEY_URI, uri.toString())
            apply()
        }
    }

    fun label(ctx: Context): String {
        val u = getCustomUri(ctx) ?: return "Default alarm"
        return u.lastPathSegment?.take(28) ?: "Custom sound"
    }
}
