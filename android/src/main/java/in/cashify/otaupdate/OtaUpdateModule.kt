package `in`.cashify.otaupdate

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * JS bridge, exposed as `NativeModules.CashifyOtaUpdate`:
 * - getOtaBundleVersion(): version of the JS bundle this session booted with.
 * - getFileSystemURL(moduleName): `file://` URL of any configured module's
 *   bundle — for JS-side loaders of non-launcher modules, and debugging.
 * - getOtaStatus(): debug snapshot of OTA state (see [OtaStatus]).
 * - getOtaLogs(): the in-memory ring buffer of captured log lines.
 * - clearOtaLogs(): clears the in-memory + persisted log buffer.
 * - checkForUpdates(): full check with a forced Remote Config refresh; resolves
 *   with the summary string.
 * - deleteDownloadedBundles(): wipes every module's downloaded + temporary bundles.
 * - setLocalSafeMode(enabled): local-only safe-mode override for debugging.
 *
 * Emits two events (see [OtaEvents]): `CashifyOtaLog` and `CashifyOtaProgress`.
 */
class OtaUpdateModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        OtaEvents.attach(reactContext)
    }

    override fun invalidate() {
        OtaEvents.detach(reactContext)
        super.invalidate()
    }

    /** Required by NativeEventEmitter on iOS parity; no-op on Android. */
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit

    override fun getName(): String = NAME

    /**
     * OTA bundle version when a downloaded bundle booted this session, else the
     * host app's versionName (the shipped asset bundle).
     */
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getOtaBundleVersion(): String {
        return OtaBundleManager.launcherLoadedBundleVersion
            ?: HostAppInfo.versionName(reactContext.applicationContext)
    }

    @ReactMethod
    fun getFileSystemURL(moduleName: String, promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            OtaLog.d("OtaUpdateModule::getFileSystemURL::$moduleName")
            try {
                val appContext = reactContext.applicationContext
                var jsBundleUri = OtaBundleManager.getJsBundleUriForModule(appContext, moduleName)

                // If it's an APK asset, copy it out to cache so JS gets a real file path.
                if (jsBundleUri.startsWith("assets://")) {
                    if (!appContext.assetExists(jsBundleUri)) {
                        throw FileNotFoundException("Asset not found: $jsBundleUri")
                    }
                    val module = OtaModuleManager.getModuleByName(moduleName)
                    val cached = copyAssetToCacheIfNeeded(appContext, jsBundleUri, module?.moduleVersion ?: "0")
                    jsBundleUri = "file://${cached.absolutePath}"
                }
                if (!jsBundleUri.startsWith("file://")) {
                    jsBundleUri = "file://$jsBundleUri"
                }
                promise.resolve(jsBundleUri)
            } catch (fnf: FileNotFoundException) {
                promise.reject(NAME, fnf.message, fnf)
            } catch (e: Exception) {
                OtaLog.e("OtaUpdateModule::getFileSystemURL error", e)
                promise.reject(NAME, "Error getting file system URL", e)
            }
        }
    }

    @ReactMethod
    fun getOtaStatus(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                promise.resolve(OtaStatus.snapshot(reactContext.applicationContext))
            } catch (t: Throwable) {
                OtaLog.e("OtaUpdateModule::getOtaStatus failed", t)
                promise.reject(NAME, t.message ?: "getOtaStatus failed", t)
            }
        }
    }

    @ReactMethod
    fun getOtaLogs(promise: Promise) {
        val array = Arguments.createArray()
        OtaLog.entries().forEach { array.pushMap(it.toWritableMap()) }
        promise.resolve(array)
    }

    @ReactMethod
    fun clearOtaLogs(promise: Promise) {
        OtaLog.clear()
        OtaLog.d("OtaUpdateModule::logs cleared from debug screen")
        promise.resolve(null)
    }

    /** Full check with a forced Remote Config refresh; resolves with the summary string. */
    @ReactMethod
    fun checkForUpdates(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                OtaLog.d("OtaUpdateModule::checkForUpdates requested from debug screen")
                promise.resolve(OtaModuleManager.loadBundles(reactContext.applicationContext, forceFetch = true))
            } catch (t: Throwable) {
                promise.reject(NAME, t.message ?: "checkForUpdates failed", t)
            }
        }
    }

    /** Wipes every module's downloaded bundles + temp files. Next launch boots the shipped asset. */
    @ReactMethod
    fun deleteDownloadedBundles(promise: Promise) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val context = reactContext.applicationContext
                OtaModuleManager.init(context)
                OtaLog.d("OtaUpdateModule::deleteDownloadedBundles requested from debug screen")
                OtaModuleManager.modules.values.forEach { OtaBundleManager.cleanupModuleBundles(context, it) }
                OtaBundleManager.cleanupTemporaryBundles(context)
                promise.resolve(null)
            } catch (t: Throwable) {
                promise.reject(NAME, t.message ?: "deleteDownloadedBundles failed", t)
            }
        }
    }

    @ReactMethod
    fun setLocalSafeMode(enabled: Boolean, promise: Promise) {
        OtaPreferences.setLocalSafeModeEnabled(reactContext.applicationContext, enabled)
        OtaLog.d("OtaUpdateModule::local safe-mode override set to $enabled")
        promise.resolve(null)
    }

    /**
     * The cache copy is keyed by [moduleVersion] so a binary update never serves
     * the previous binary's cached asset bundle.
     */
    private suspend fun copyAssetToCacheIfNeeded(
        context: Context,
        assetUri: String,
        moduleVersion: String
    ): File = withContext(Dispatchers.IO) {
        val relativePath = assetUri
            .removePrefix("assets://")
            .trim('/')
            .also { require(it.isNotEmpty()) { "Empty asset path: '$assetUri'" } }

        val outFile = File(context.cacheDir, "ota-assets/$moduleVersion/$relativePath")
        if (outFile.exists()) return@withContext outFile
        outFile.parentFile?.takeIf { !it.exists() }?.mkdirs()

        context.openAssetOrNull(assetUri)
            ?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw FileNotFoundException("Failed to open asset stream: $relativePath")

        outFile
    }

    companion object {
        const val NAME = "CashifyOtaUpdate"
    }
}
