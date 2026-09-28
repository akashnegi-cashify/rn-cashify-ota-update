package `in`.cashify.otaupdate

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses the module map from the app.json packaged into APK assets (key
 * `otaModules`, falling back to `legoModules` for lego-style apps) and
 * orchestrates the background OTA check for every module with `otaUpdates: true`.
 */
object OtaModuleManager {

    @Volatile
    internal var modules: Map<String, OtaModule> = emptyMap()
        private set

    @Volatile
    private var initialized = false

    // Thread-safe map of the bundle version actually resolved per module.
    private val bundleVersionMap = ConcurrentHashMap<String, String>()

    private val checkMutex = Mutex()

    @Volatile
    var checkInFlight: Boolean = false
        private set

    /**
     * Synchronous, local-only app.json parse — safe to call on the launch path.
     * Never throws; on any failure the module map stays empty and OTA is disabled.
     */
    fun init(context: Context, assetFileName: String = "app.json") {
        if (initialized) return
        try {
            val jsonString =
                context.assets.open(assetFileName).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val modulesJson = root.optJSONObject("otaModules") ?: root.optJSONObject("legoModules")
            if (modulesJson == null) {
                OtaLog.w("OtaModuleManager::init::no otaModules/legoModules in $assetFileName, OTA disabled")
                initialized = true
                return
            }
            val parsed = mutableMapOf<String, OtaModule>()
            modulesJson.keys().forEach { key ->
                val moduleJson = modulesJson.getJSONObject(key)
                parsed[key] = OtaModule(
                    moduleName = moduleJson.getString("moduleName"),
                    modulePath = moduleJson.getString("modulePath"),
                    configKey = moduleJson.getString("configKey"),
                    scheme = moduleJson.optString("scheme", ""),
                    moduleVersion = moduleJson.getString("moduleVersion"),
                    bundlePriority = moduleJson.optInt("bundlePriority", Int.MAX_VALUE),
                    otaUpdates = moduleJson.optBoolean("otaUpdates", false),
                    launcher = moduleJson.optBoolean("launcher", false),
                )
            }
            modules = parsed.toMap()
            initialized = true
            OtaLog.d("OtaModuleManager::init::modules: ${modules.keys.joinToString(",")}")
        } catch (t: Throwable) {
            OtaLog.e("OtaModuleManager::init failed, OTA disabled: ${t.message}", t)
            modules = emptyMap()
            initialized = true
        }
    }

    fun loadBundlesAsync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch { loadBundles(context) }
    }

    /**
     * One full check. Serialised: a second caller waits for the first and returns its own
     * (fresh) pass afterwards. Records `last_check_at` / `last_check_result` on every exit.
     * @param forceFetch bypass the Remote Config cache first (debug "Check now").
     * @return human-readable summary, e.g. "CashifyOps: downloaded 8.2.0".
     */
    suspend fun loadBundles(context: Context, forceFetch: Boolean = false): String = checkMutex.withLock {
        checkInFlight = true
        val summary = try {
            runCheck(context, forceFetch)
        } catch (t: Throwable) {
            OtaLog.e("OtaModuleManager::loadBundles failed: ${t.message}", t)
            "error: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            checkInFlight = false
        }
        OtaPreferences.setLastCheck(context, System.currentTimeMillis(), summary)
        OtaLog.d("OtaModuleManager::check result: $summary")
        summary
    }

    private suspend fun runCheck(context: Context, forceFetch: Boolean): String {
        init(context)
        val otaModules = modules.values.filter { it.otaUpdates }
        if (otaModules.isEmpty()) {
            OtaLog.d("OtaModuleManager::no OTA modules configured, skipping")
            return "skipped: no OTA modules configured"
        }
        OtaBundleManager.cleanupTemporaryBundles(context)
        if (!NetworkUtil.isNetworkAvailable(context)) {
            OtaLog.d("OtaModuleManager::network not available, skipping bundle check")
            return "skipped: network not available"
        }
        if (forceFetch && !OtaRemoteConfig.refresh(context)) {
            OtaLog.w("OtaModuleManager::forced Remote Config refresh failed, using cached values")
        }
        if (OtaRemoteConfig.getEnableSafeMode(context)) {
            OtaLog.d("OtaModuleManager::global safe mode enabled, clearing all module bundles")
            otaModules.forEach { OtaBundleManager.cleanupModuleBundles(context, it) }
            OtaPreferences.setSafeModeEnabled(context, true)
            return "skipped: global safe mode enabled"
        }
        OtaPreferences.setSafeModeEnabled(context, false)

        val results = mutableListOf<String>()
        val sortedModules = otaModules.sortedWith(compareBy({ it.bundlePriority }, { it.moduleName }))
        sortedModules.forEach { module ->
            if (OtaRemoteConfig.getModuleEnableSafeMode(context, module)) {
                OtaLog.d("OtaModuleManager::module safe mode enabled for ${module.moduleName}, clearing its bundles")
                OtaBundleManager.cleanupModuleBundles(context, module)
                OtaPreferences.setModuleSafeModeEnabled(context, module.configKey, true)
                results += "${module.moduleName}: skipped: module safe mode enabled"
                return@forEach
            }
            OtaPreferences.setModuleSafeModeEnabled(context, module.configKey, false)
            OtaLog.d("OtaModuleManager::checking module: ${module.moduleName}, priority: ${module.bundlePriority}")
            val result = OtaBundleManager.downloadBundleIfNeeded(context, module)
            OtaBundleManager.cleanupStaleBundles(context, module)
            results += "${module.moduleName}: ${result.describe()}"
        }
        return results.joinToString("; ")
    }

    fun launcherModule(): OtaModule? = modules.values.firstOrNull { it.launcher }

    fun getModuleByName(moduleName: String): OtaModule? = modules[moduleName]

    fun getBundleVersion(moduleName: String): String? = bundleVersionMap[moduleName]

    fun setBundleVersion(moduleName: String, version: String) {
        bundleVersionMap[moduleName] = version
    }
}
