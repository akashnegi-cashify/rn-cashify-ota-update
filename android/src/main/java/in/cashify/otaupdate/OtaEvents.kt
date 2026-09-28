package `in`.cashify.otaupdate

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * JS event fan-out. The bridge module attaches its ReactApplicationContext on
 * construction and detaches on invalidate; emits are no-ops without an active
 * React instance (e.g. the launch path before JS is up).
 */
internal object OtaEvents {
    const val EVENT_LOG = "CashifyOtaLog"
    const val EVENT_PROGRESS = "CashifyOtaProgress"

    @Volatile
    private var reactContext: ReactApplicationContext? = null

    fun attach(context: ReactApplicationContext) { reactContext = context }

    fun detach(context: ReactApplicationContext) { if (reactContext === context) reactContext = null }

    fun emitLog(entry: OtaLogEntry) = emit(EVENT_LOG, entry.toWritableMap())

    fun emitProgress(moduleName: String, bytesRead: Long, totalBytes: Long, done: Boolean) {
        emit(EVENT_PROGRESS, Arguments.createMap().apply {
            putString("moduleName", moduleName)
            putDouble("bytesRead", bytesRead.toDouble())
            putDouble("totalBytes", totalBytes.toDouble())
            putBoolean("done", done)
        })
    }

    private fun emit(name: String, params: WritableMap) {
        val context = reactContext ?: return
        if (!context.hasActiveReactInstance()) return
        try {
            context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, params)
        } catch (_: Throwable) {
            // Never let an event failure surface into OTA logic.
        }
    }
}
