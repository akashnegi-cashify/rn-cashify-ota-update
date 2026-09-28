package `in`.cashify.otaupdate

/** Outcome of one module's download-if-needed pass. `describe()` is what the debug screen shows. */
sealed class OtaCheckResult {
    data class Downloaded(val version: String) : OtaCheckResult()
    object UpToDate : OtaCheckResult()
    data class Skipped(val reason: String) : OtaCheckResult()
    data class Failed(val message: String) : OtaCheckResult()

    fun describe(): String = when (this) {
        is Downloaded -> "downloaded $version"
        UpToDate -> "up to date"
        is Skipped -> "skipped: $reason"
        is Failed -> "error: $message"
    }
}

data class OtaBundleInfo(
    val version: String,
    val path: String,
    val sizeBytes: Long,
    val valid: Boolean,
    val modifiedAt: Long,
)
