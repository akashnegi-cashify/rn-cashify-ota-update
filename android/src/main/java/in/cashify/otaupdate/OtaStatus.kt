package `in`.cashify.otaupdate

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/** Debug snapshot for the JS `getOtaStatus()` bridge call. Local-only: never fetches. */
internal object OtaStatus {

    fun snapshot(context: Context): WritableMap {
        OtaModuleManager.init(context)
        val module = OtaModuleManager.launcherModule()
        val bootedFromDisk = OtaBundleManager.launcherLoadedBundleVersion != null
        val bundleUrl = OtaRemoteConfig.cachedString("rn_bundle_url")
        val latestVersion = module?.let { OtaRemoteConfig.cachedString("rnb_${it.configKey}_latest_version") } ?: ""

        return Arguments.createMap().apply {
            putString("platform", "android")
            putBoolean("debuggable", HostAppInfo.isDebuggable(context))
            putString("installedAppVersion", HostAppInfo.versionName(context))
            putString("bootedBundleVersion", OtaBundleManager.launcherLoadedBundleVersion ?: HostAppInfo.versionName(context))
            putBoolean("bootedFromDisk", bootedFromDisk)

            if (module == null) putNull("module") else putMap("module", Arguments.createMap().apply {
                putString("moduleName", module.moduleName)
                putString("modulePath", module.modulePath)
                putString("configKey", module.configKey)
                putString("moduleVersion", module.moduleVersion)
                putBoolean("otaUpdates", module.otaUpdates)
            })

            putMap("remote", Arguments.createMap().apply {
                putString("bundleUrl", bundleUrl)
                putString("latestVersion", latestVersion)
                putBoolean("safeMode", OtaRemoteConfig.cachedBoolean("rn_enable_safe_mode"))
                putBoolean("moduleSafeMode", module?.let { OtaRemoteConfig.cachedBoolean("rnb_${it.configKey}_enable_safe_mode") } ?: false)
                putString("lastFetchStatus", OtaRemoteConfig.lastFetchStatus())
                val at = OtaRemoteConfig.lastFetchTimeMillis()
                if (at == null) putNull("lastFetchAt") else putDouble("lastFetchAt", at.toDouble())
                if (module != null && bundleUrl.isNotEmpty() && latestVersion.isNotEmpty()) {
                    putString("resolvedDownloadUrl", OtaBundleManager.buildJsBundleRemoteUrl(bundleUrl, module, latestVersion))
                } else putNull("resolvedDownloadUrl")
            })

            putMap("local", Arguments.createMap().apply {
                putBoolean("safeModePersisted", OtaPreferences.isSafeModeEnabled(context))
                putBoolean("moduleSafeModePersisted", module?.let { OtaPreferences.isModuleSafeModeEnabled(context, it.configKey) } ?: false)
                putBoolean("localSafeModeOverride", OtaPreferences.isLocalSafeModeEnabled(context))
                val at = OtaPreferences.getLastCheckAt(context)
                if (at == null) putNull("lastCheckAt") else putDouble("lastCheckAt", at.toDouble())
                putString("lastCheckResult", OtaPreferences.getLastCheckResult(context))
                putBoolean("checkInFlight", OtaModuleManager.checkInFlight)
            })

            val bundles = Arguments.createArray()
            if (module != null) {
                OtaBundleManager.listBundles(context, module).forEach { info ->
                    bundles.pushMap(Arguments.createMap().apply {
                        putString("version", info.version)
                        putString("path", info.path)
                        putDouble("sizeBytes", info.sizeBytes.toDouble())
                        putBoolean("valid", info.valid)
                        putDouble("modifiedAt", info.modifiedAt.toDouble())
                        putBoolean("willBootNextLaunch", OtaBundleManager.willBootNextLaunch(context, module, info.version))
                    })
                }
            }
            putArray("bundles", bundles)
        }
    }
}
