package `in`.cashify.otaupdate

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One captured log line. `level` is "D", "W" or "E". */
data class OtaLogEntry(val ts: Long, val level: String, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("ts", ts).put("level", level).put("message", message)

    fun toWritableMap(): WritableMap = Arguments.createMap().apply {
        putDouble("ts", ts.toDouble())
        putString("level", level)
        putString("message", message)
    }

    companion object {
        fun fromJson(json: JSONObject): OtaLogEntry =
            OtaLogEntry(json.getLong("ts"), json.getString("level"), json.getString("message"))
    }
}

/**
 * All OTA logging goes through here. Every call:
 *  1. writes to logcat with the unchanged tag `CashifyOTA` (so `adb logcat -s CashifyOTA`
 *     output is byte-identical to before),
 *  2. appends to an in-memory ring buffer of [MAX_ENTRIES],
 *  3. appends one JSON line to `<filesDir>/cashify_ota/logs.jsonl` off-thread so the
 *     log survives process death (the interesting lines are written on the launch
 *     BEFORE the one you are looking at); every 2*MAX_ENTRIES appends the file is
 *     instead rewritten wholesale from the in-memory buffer, capping its growth,
 *  4. emits `CashifyOtaLog` to JS when a React context is attached.
 * Never throws — logging must not be able to break OTA.
 */
internal object OtaLog {
    const val TAG = "CashifyOTA"
    const val MAX_ENTRIES = 300
    private const val DIR_NAME = "cashify_ota"
    private const val FILE_NAME = "logs.jsonl"

    private val lock = Any()
    private val buffer = ArrayDeque<OtaLogEntry>(MAX_ENTRIES)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "CashifyOTA-log") }

    /**
     * Appends since the file was last rewritten from a buffer snapshot; caps file
     * growth (fix 1). Guarded by [lock] — mutated only inside [record]'s
     * synchronized block, never via a separate atomic/check-then-act.
     */
    private var appendsSinceRewrite = 0

    @Volatile
    private var logFile: File? = null

    /** Loads the persisted tail into the buffer. Idempotent; safe on the launch path (small file). */
    fun init(context: Context) {
        if (logFile != null) return
        try {
            val file = File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)
            logFile = file
            if (!file.exists()) return
            val lines = file.readLines()
            val tail = lines.takeLast(MAX_ENTRIES)
            val entries = tail.mapNotNull { line ->
                try { OtaLogEntry.fromJson(JSONObject(line)) } catch (_: Exception) { null }
            }
            synchronized(lock) {
                buffer.clear()
                buffer.addAll(entries)
            }
            if (tail.size != lines.size) rewriteFile(entries)
        } catch (t: Throwable) {
            Log.e(TAG, "OtaLog::init failed: ${t.message}", t)
        }
    }

    fun d(message: String) {
        try {
            Log.d(TAG, message)
            record("D", message)
        } catch (_: Throwable) {}
    }

    fun w(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.w(TAG, message, t) else Log.w(TAG, message)
            record("W", withCause(message, t))
        } catch (_: Throwable) {}
    }

    fun e(message: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
            record("E", withCause(message, t))
        } catch (_: Throwable) {}
    }

    fun entries(): List<OtaLogEntry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        io.execute { try { logFile?.writeText("") } catch (_: Throwable) {} }
    }

    private fun withCause(message: String, t: Throwable?): String =
        if (t == null || message.contains(t.message ?: "\u0000")) message else "$message (${t.javaClass.simpleName}: ${t.message})"

    private fun record(level: String, message: String) {
        val entry = OtaLogEntry(System.currentTimeMillis(), level, message)
        // Buffer mutation, the append-vs-rewrite decision, the counter update, and the
        // io.execute submission all happen under the SAME lock so that (a) the counter's
        // check-then-act can't race across threads and (b) submissions to the (single-
        // threaded) io executor happen in the same order as buffer mutations — otherwise
        // a rewrite snapshot from one thread could race an append from another and either
        // duplicate or drop a line in the file.
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            // Cap file growth between launches: every 2*MAX_ENTRIES appends, rewrite the
            // file from a fresh buffer snapshot instead of appending forever (fix 1).
            if (++appendsSinceRewrite >= 2 * MAX_ENTRIES) {
                appendsSinceRewrite = 0
                rewriteFile(buffer.toList())
            } else {
                io.execute {
                    try { logFile?.appendText(entry.toJson().toString() + "\n") } catch (_: Throwable) {}
                }
            }
        }
        OtaEvents.emitLog(entry)
    }

    private fun rewriteFile(entries: List<OtaLogEntry>) {
        io.execute {
            try { logFile?.writeText(entries.joinToString("") { it.toJson().toString() + "\n" }) } catch (_: Throwable) {}
        }
    }
}
