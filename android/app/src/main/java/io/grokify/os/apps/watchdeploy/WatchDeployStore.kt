package io.grokify.os.apps.watchdeploy

import android.content.Context

/** Persist last wireless ADB target + last installed wear version. */
class WatchDeployStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var hostPort: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    /** Last pairing address (IP:pairingPort) — optional, for re-pair UI only. */
    var pairHostPort: String
        get() = prefs.getString(KEY_PAIR_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIR_HOST, value.trim()).apply()

    var lastSerial: String
        get() = prefs.getString(KEY_SERIAL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERIAL, value.trim()).apply()

    var lastInstalledVersionCode: Int
        get() = prefs.getInt(KEY_WEAR_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_WEAR_CODE, value).apply()

    var lastInstalledVersionName: String
        get() = prefs.getString(KEY_WEAR_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEAR_NAME, value).apply()

    var lastLog: String
        get() = prefs.getString(KEY_LOG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LOG, value.take(12_000)).apply()

    companion object {
        private const val PREFS = "watch_deploy"
        private const val KEY_HOST = "host_port"
        private const val KEY_PAIR_HOST = "pair_host_port"
        private const val KEY_SERIAL = "last_serial"
        private const val KEY_WEAR_CODE = "wear_version_code"
        private const val KEY_WEAR_NAME = "wear_version_name"
        private const val KEY_LOG = "last_log"
    }
}
