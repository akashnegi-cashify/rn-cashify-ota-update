package `in`.cashify.otaupdate

import android.content.Context

object OtaPreferences {

    private const val PREFS_NAME = "cashify_ota_prefs"
    private const val KEY_SAFE_MODE = "safe_mode_enabled"
    private const val KEY_APP_VERSION = "app_version"
    private const val KEY_LOCAL_SAFE_MODE = "local_safe_mode_override"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_LAST_CHECK_RESULT = "last_check_result"

    // Prefixed so a module configKey can never collide with the global
    // safe-mode key (e.g. configKey "enabled").
    private const val KEY_MODULE_SAFE_MODE_PREFIX = "module_safe_mode_"

    fun isSafeModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_MODE, false)
    }

    fun setSafeModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAFE_MODE, enabled).apply()
    }

    fun isModuleSafeModeEnabled(context: Context, configKey: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MODULE_SAFE_MODE_PREFIX + configKey, false)
    }

    fun setModuleSafeModeEnabled(context: Context, configKey: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MODULE_SAFE_MODE_PREFIX + configKey, enabled).apply()
    }

    fun getStoredAppVersion(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_VERSION, null)
    }

    fun setStoredAppVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_APP_VERSION, version).apply()
    }

    /** Debug-screen override: NEVER written by the Remote Config path. Launch path only. */
    fun isLocalSafeModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_LOCAL_SAFE_MODE, false)

    fun setLocalSafeModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOCAL_SAFE_MODE, enabled).apply()
    }

    fun getLastCheckAt(context: Context): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK_AT, -1L).takeIf { it > 0 }

    fun getLastCheckResult(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_CHECK_RESULT, null)

    fun setLastCheck(context: Context, at: Long, result: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK_AT, at).putString(KEY_LAST_CHECK_RESULT, result).apply()
    }
}
