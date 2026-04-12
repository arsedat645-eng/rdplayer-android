package com.rdplayer.app.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage

enum class PlayerMode(val storageValue: String) {
    INTERNAL("internal"),
    EXTERNAL("external"),
    ASK_EVERY_TIME("ask_every_time");

    companion object {
        fun fromStoredValue(value: String?): PlayerMode? {
            return values().firstOrNull { it.storageValue == value }
        }
    }
}

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("rd_player_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_EXTERNAL_PLAYER = "external_player"
        private const val KEY_PLAYER_MODE = "player_mode"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trimEnd('/')).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var playerMode: PlayerMode
        get() {
            PlayerMode.fromStoredValue(prefs.getString(KEY_PLAYER_MODE, null))?.let { storedMode ->
                return storedMode
            }
            return if (prefs.getBoolean(KEY_EXTERNAL_PLAYER, false)) {
                PlayerMode.EXTERNAL
            } else {
                PlayerMode.INTERNAL
            }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_PLAYER_MODE, value.storageValue)
                .putBoolean(KEY_EXTERNAL_PLAYER, value == PlayerMode.EXTERNAL)
                .apply()
        }

    var externalPlayer: Boolean
        get() = playerMode == PlayerMode.EXTERNAL
        set(value) {
            playerMode = if (value) PlayerMode.EXTERNAL else PlayerMode.INTERNAL
        }

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && setupCompleted

    fun savePlaybackPosition(videoId: String, positionMs: Long) {
        prefs.edit().putLong("pos_ms_$videoId", positionMs).apply()
    }

    fun savePlaybackPositions(videoIds: Collection<String>, positionMs: Long) {
        val editor = prefs.edit()
        videoIds.forEach { videoId ->
            editor.putLong("pos_ms_$videoId", positionMs)
        }
        editor.apply()
    }

    fun getPlaybackPosition(videoId: String): Long {
        return prefs.getLong("pos_ms_$videoId", 0L)
    }

    fun getPlaybackPosition(videoIds: Collection<String>): Long {
        for (videoId in videoIds) {
            val value = prefs.getLong("pos_ms_$videoId", 0L)
            if (value > 0L) {
                return value
            }
        }
        return 0L
    }

    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    fun clearCache(context: Context) {
        WebStorage.getInstance().deleteAllData()
    }

    fun resetApp(context: Context) {
        clearCookies()
        clearCache(context)
        prefs.edit().clear().apply()
    }
}
