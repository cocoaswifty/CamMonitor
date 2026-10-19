package com.a0soft.gphone.camonitor.data.webrtc

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * TURN credentials are provisioned by the device owner, never checked into source control.
 * Do not silently downgrade to plaintext SharedPreferences if encryption fails.
 */
data class TurnSettings(
    val uri: String = "",
    val username: String = "",
    val credential: String = ""
) {
    fun isConfigured(): Boolean = isValidUri(uri) &&
        username.isNotBlank() && credential.isNotBlank()

    companion object {
        fun isValidUri(value: String): Boolean {
            val trimmed = value.trim()
            return (trimmed.startsWith("turn:") || trimmed.startsWith("turns:")) &&
                trimmed.length in 10..255 &&
                trimmed.none { it.isWhitespace() || it == '#' }
        }

        private fun prefs(context: Context) = EncryptedSharedPreferences.create(
            context.applicationContext,
            "cammonitor_turn_settings",
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        fun load(context: Context): TurnSettings {
            val secure = prefs(context)
            return TurnSettings(
                uri = secure.getString("uri", "").orEmpty(),
                username = secure.getString("username", "").orEmpty(),
                credential = secure.getString("credential", "").orEmpty()
            )
        }

        fun save(context: Context, settings: TurnSettings) {
            require(settings.uri.isBlank() || settings.isConfigured()) {
                "TURN URI, username and credential are all required"
            }
            prefs(context).edit()
                .putString("uri", settings.uri.trim())
                .putString("username", settings.username.trim())
                .putString("credential", settings.credential)
                .apply()
        }
    }
}
