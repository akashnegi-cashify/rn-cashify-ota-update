package `in`.cashify.otaupdate

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Version selection, download and cleanup for OTA bundles.
 *
 * The LAUNCH path ([getLauncherBundleFilePathLocal]) is synchronous and
 * local-only — no Remote Config, no network — so it can run inside the host's
 * lazy `reactHost` block without blocking the first frame. Safe-mode kill
 * switches (global and per-module) from Remote Config are applied by the
 * background check and persisted via [OtaPreferences]; the launch path only
 * reads those persisted flags.
 *
 * The launcher module's shipped bundle is the host's stock flat asset
 * (`assets://index.android.bundle`), so its asset baseline version comes from
 * `moduleVersion` in app.json. Returning null hands loading back to the host's
 * default asset loader.
 */
object OtaBundleManager {

    // One lock per module so concurrent downloadBundleIfNeeded calls for the
    // SAME module serialize instead of downloading the same bundle twice.
    private val downloadMutex = ConcurrentHashMap<String, Mutex>()

    // Live progress listeners per module; an in-flight download fans out to
    // whoever is registered at the time of each network read.
    private val progressListeners =
        ConcurrentHashMap<String, CopyOnWriteArraySet<BundleDownloadProgressListener>>()

    /** Version of the disk bundle the launcher actually booted with, if any. */
    @Volatile
    var launcherLoadedBundleVersion: String? = null
        private set

    /**
     * LAUNCH PATH — decides which bundle the ReactHost boots. Returns an absolute
     * file path of a valid downloaded bundle strictly newer than the shipped one,
     * or null (-> host loads the default APK asset / Metro in debug). Never throws.
     */
    fun getLauncherBundleFilePathLocal(context: Context): String? {
        val tag = "OtaBundleManager::getLauncherBundleFilePathLocal"
        return try {
            if (HostAppInfo.isDebuggable(context)) {
                OtaLog.d("$tag::debuggable build, using default bundle source")
                return null
            }
            if (OtaPreferences.isSafeModeEnabled(context)) {
                OtaLog.d("$tag::safe mode enabled, using asset bundle")
                return null
            }
            if (OtaPreferences.isLocalSafeModeEnabled(context)) {
                OtaLog.d("$tag::local safe-mode override enabled, using asset bundle")
                return null
            }
            val module = OtaModuleManager.launcherModule()
            if (module == null) {
                OtaLog.d("$tag::no launcher module configured, using asset bundle")
                return null
            }
            if (OtaPreferences.isModuleSafeModeEnabled(context, module.configKey)) {
                OtaLog.d("$tag::module safe mode enabled for ${module.moduleName}, using asset bundle")
                return null
            }
            val assetVersion = getJsBundleAssetVersion(module)
            val diskVersion = getJsBundleDiskVersion(context, module)
            if (diskVersion == null) {
                OtaLog.d("$tag::no valid disk bundle, using asset bundle ($assetVersion)")
                OtaModuleManager.setBundleVersion(module.moduleName, assetVersion ?: "")
                return null
            }
            // Asset wins ties: disk must be STRICTLY newer than the shipped bundle.
            if (assetVersion != null && listOf(diskVersion, assetVersion).semanticMax() == assetVersion) {
                OtaLog.d("$tag::disk $diskVersion <= asset $assetVersion, using asset bundle")
                OtaModuleManager.setBundleVersion(module.moduleName, assetVersion)
                return null
            }
            val bundleFile = buildJsBundleFilePath(context, module, diskVersion)
            launcherLoadedBundleVersion = diskVersion
            OtaModuleManager.setBundleVersion(module.moduleName, diskVersion)
            OtaLog.d("$tag::loading disk bundle $diskVersion: ${bundleFile.absolutePath}")
            bundleFile.absolutePath
        } catch (t: Throwable) {
            OtaLog.e("$tag::failed, falling back to asset bundle: ${t.message}", t)
            null
        }
    }

    /**
     * Resolves any module's bundle for the ScriptResolver bridge: valid disk
     * bundle first, launcher's flat asset as fallback. Throws when nothing exists
     * (non-launcher module with no downloaded bundle, or module safe-mode on a
     * non-launcher module).
     */
    fun getJsBundleUriForModule(context: Context, moduleName: String): String {
        val tag = "OtaBundleManager::getJsBundleUriForModule::$moduleName"
        val module = OtaModuleManager.getModuleByName(moduleName)
            ?: throw IllegalArgumentException("$tag::module not found")

        val safeMode = OtaPreferences.isSafeModeEnabled(context) ||
            OtaPreferences.isLocalSafeModeEnabled(context) ||
            OtaPreferences.isModuleSafeModeEnabled(context, module.configKey)
        if (!safeMode) {
            val diskVersion = getJsBundleDiskVersion(context, module)
            val assetVersion = getJsBundleAssetVersion(module)
            if (diskVersion != null &&
                (assetVersion == null || listOf(diskVersion, assetVersion).semanticMax() == diskVersion) &&
                diskVersion != assetVersion
            ) {
                val bundleFile = buildJsBundleFilePath(context, module, diskVersion)
                OtaModuleManager.setBundleVersion(module.moduleName, diskVersion)
                OtaLog.d("$tag::disk bundle $diskVersion")
                return bundleFile.absolutePath
            }
        }
        if (module.launcher) {
            OtaModuleManager.setBundleVersion(module.moduleName, module.moduleVersion)
            OtaLog.d("$tag::asset bundle ${module.moduleVersion}")
            return "assets://${getBundleName()}"
        }
        throw IllegalStateException("$tag::no bundle available for non-launcher module")
    }

    suspend fun downloadBundleIfNeeded(
        context: Context,
        module: OtaModule,
        onProgress: BundleDownloadProgressListener? = null
    ): OtaCheckResult {
        val tag = "OtaBundleManager::downloadBundleIfNeeded::${module.moduleName}"
        OtaLog.d(tag)
        val listeners = progressListeners.getOrPut(module.moduleName) { CopyOnWriteArraySet() }
        onProgress?.let { listeners.add(it) }
        var lastEmitAt = 0L
        val fanOutProgress: BundleDownloadProgressListener = { bytesRead, totalBytes, done ->
            listeners.forEach { listener ->
                try { listener(bytesRead, totalBytes, done) } catch (e: Exception) { OtaLog.w("$tag::progress listener failed", e) }
            }
            val now = System.currentTimeMillis()
            if (done || now - lastEmitAt >= 250L) {
                lastEmitAt = now
                OtaEvents.emitProgress(module.moduleName, bytesRead, totalBytes, done)
            }
        }
        try {
            val mutex = downloadMutex.getOrPut(module.moduleName) { Mutex() }
            if (mutex.isLocked) OtaLog.d("$tag::download already in flight, waiting for it to finish")
            return mutex.withLock {
                try {
                    val remoteVersion = OtaRemoteConfig.getBundleLatestVersion(context, module)
                    OtaLog.d("$tag::remoteVersion: $remoteVersion")
                    if (remoteVersion.isEmpty()) {
                        OtaLog.d("$tag::remoteVersion is empty, skip download")
                        return@withLock OtaCheckResult.Skipped("rnb_${module.configKey}_latest_version empty")
                    }
                    if (!remoteVersion.isSemanticVersion()) {
                        OtaLog.w("$tag::remoteVersion '$remoteVersion' is not a valid version, skip download")
                        return@withLock OtaCheckResult.Skipped("remote version '$remoteVersion' invalid")
                    }
                    val assetVersion = getJsBundleAssetVersion(module)
                    OtaLog.d("$tag::assetVersion: $assetVersion")
                    if (assetVersion != null && listOf(assetVersion, remoteVersion).semanticMax() == assetVersion) {
                        OtaLog.d("$tag::assetVersion >= remoteVersion, skip download")
                        return@withLock OtaCheckResult.UpToDate
                    }
                    val diskVersion = getJsBundleDiskVersion(context, module)
                    if (diskVersion != null && listOf(diskVersion, remoteVersion).semanticMax() == diskVersion) {
                        OtaLog.d("$tag::diskVersion $diskVersion >= remoteVersion, skip download")
                        return@withLock OtaCheckResult.UpToDate
                    }
                    val bundleUrl = OtaRemoteConfig.getBundleUrl(context)
                    if (bundleUrl.isEmpty()) {
                        OtaLog.d("$tag::rn_bundle_url is empty, OTA disabled, skip download")
                        return@withLock OtaCheckResult.Skipped("rn_bundle_url empty")
                    }
                    OtaLog.d("$tag::downloading remote bundle $remoteVersion")
                    if (downloadRemoteBundle(context, tag, module, bundleUrl, remoteVersion, fanOutProgress)) {
                        OtaCheckResult.Downloaded(remoteVersion)
                    } else {
                        OtaCheckResult.Failed("bundle $remoteVersion rejected (missing marker or install failed)")
                    }
                } catch (e: Exception) {
                    OtaLog.e("$tag::error: ${e.message}", e)
                    OtaCheckResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        } finally {
            onProgress?.let { listeners.remove(it) }
        }
    }

    suspend fun cleanupStaleBundles(context: Context, module: OtaModule) {
        val tag = "OtaBundleManager::cleanupStaleBundles::${module.moduleName}"
        val moduleDir = File(getFilesDir(context), module.modulePath)
        if (!moduleDir.exists()) return
        val versionDirs = moduleDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (versionDirs.isEmpty()) return

        val assetVersion = getJsBundleAssetVersion(module)
        val remoteVersion = try {
            OtaRemoteConfig.getBundleLatestVersion(context, module)
        } catch (e: Exception) {
            ""
        }
        OtaLog.d("$tag::assetVersion: $assetVersion, remoteVersion: $remoteVersion")

        val invalidVersions = mutableListOf<String>()
        for (versionDir in versionDirs) {
            // empty directory
            if (versionDir.listFiles()?.isEmpty() == true) {
                OtaLog.d("$tag::empty version directory: ${versionDir.name}")
                invalidVersions.add(versionDir.name)
                versionDir.deleteRecursively()
                continue
            }
            // rollback: remote was lowered below this version
            if (remoteVersion.isNotEmpty() && remoteVersion != versionDir.name &&
                listOf(remoteVersion, versionDir.name).semanticMin() == remoteVersion
            ) {
                OtaLog.d("$tag::rollback detected, deleting: ${versionDir.name}")
                invalidVersions.add(versionDir.name)
                versionDir.deleteRecursively()
                continue
            }
            // stale: not newer than the shipped bundle
            if (assetVersion != null &&
                listOf(assetVersion, versionDir.name).semanticMax() == assetVersion
            ) {
                OtaLog.d("$tag::stale version detected, deleting: ${versionDir.name}")
                invalidVersions.add(versionDir.name)
                versionDir.deleteRecursively()
                continue
            }
            val bundleFile = File(versionDir, getBundleName())
            if (!bundleFile.exists() || !BundleDownloader.hasMarker(bundleFile)) {
                OtaLog.d("$tag::missing/corrupt bundle, deleting: ${versionDir.name}")
                invalidVersions.add(versionDir.name)
                versionDir.deleteRecursively()
                continue
            }
        }

        val validVersionDirs = versionDirs.filter { !invalidVersions.contains(it.name) }
        if (validVersionDirs.size <= 1) return
        // Keep only the newest valid version.
        val latestVersion = validVersionDirs.map { it.name }.semanticMax()
        for (versionDir in validVersionDirs) {
            if (versionDir.name != latestVersion) {
                OtaLog.d("$tag::deleting old version directory: ${versionDir.name}")
                versionDir.deleteRecursively()
            }
        }
    }

    fun cleanupTemporaryBundles(context: Context) {
        val tag = "OtaBundleManager::cleanupTemporaryBundles"
        val tempFiles = getTemporaryDir(context).listFiles()?.filter { it.name.endsWith(".tmp") }
            ?: emptyList()
        for (tempFile in tempFiles) {
            try {
                tempFile.delete()
                OtaLog.d("$tag::deleted temporary file: ${tempFile.absolutePath}")
            } catch (e: Exception) {
                OtaLog.e("$tag::failed to delete ${tempFile.absolutePath}: ${e.message}")
            }
        }
    }

    fun cleanupModuleBundles(context: Context, module: OtaModule) {
        val tag = "OtaBundleManager::cleanupModuleBundles::${module.moduleName}"
        val moduleDir = File(getFilesDir(context), module.modulePath)
        if (moduleDir.exists()) {
            val result = moduleDir.deleteRecursively()
            OtaLog.d("$tag::deleted module directory: ${moduleDir.absolutePath} result: $result")
        }
    }

    private fun downloadRemoteBundle(
        context: Context,
        tag: String,
        module: OtaModule,
        bundleUrl: String,
        remoteVersion: String,
        onProgress: BundleDownloadProgressListener? = null
    ): Boolean {
        val destinationFile = buildJsBundleFilePath(context, module, remoteVersion)
        val tempFile = File(getTemporaryDir(context), UUID.randomUUID().toString() + ".tmp")
        val remoteUrl = buildJsBundleRemoteUrl(bundleUrl, module, remoteVersion)

        val downloadFilePath = BundleDownloader.downloadFileSync(remoteUrl, tempFile, onProgress)
        if (downloadFilePath == null) {
            if (tempFile.exists()) tempFile.delete()
            throw Exception("$tag::failed to download bundle from $remoteUrl")
        }

        if (!BundleDownloader.hasMarker(tempFile)) {
            OtaLog.d("$tag::downloaded bundle has no marker, deleting")
            tempFile.delete()
            return false
        }

        try {
            destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            // cacheDir and filesDir share a filesystem, so this rename is atomic.
            val renamed = tempFile.renameTo(destinationFile)
            if (!renamed) {
                OtaLog.e("$tag::renameTo failed, cleaning up")
                tempFile.delete()
                if (destinationFile.exists()) destinationFile.delete()
                return false
            }
            OtaLog.d("$tag::bundle installed at: ${destinationFile.absolutePath}")
            return true
        } catch (e: Exception) {
            OtaLog.e("$tag::error moving file: ${e.message}")
            tempFile.delete()
            if (destinationFile.exists()) destinationFile.delete()
            return false
        }
    }

    internal fun buildJsBundleRemoteUrl(bundleUrl: String, module: OtaModule, version: String): String {
        return "${bundleUrl.trimEnd('/')}/${module.modulePath}/$version/${getBundleName()}.zip".also {
            OtaLog.d("OtaBundleManager::buildJsBundleRemoteUrl::url: $it")
        }
    }

    /** Every version directory on disk for the module, valid or not, newest first. */
    internal fun listBundles(context: Context, module: OtaModule): List<OtaBundleInfo> {
        val moduleDir = File(getFilesDir(context), module.modulePath)
        val dirs = moduleDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.map { dir ->
            val file = File(dir, getBundleName())
            OtaBundleInfo(
                version = dir.name,
                path = file.absolutePath,
                sizeBytes = if (file.exists()) file.length() else 0L,
                valid = file.exists() && BundleDownloader.hasMarker(file),
                modifiedAt = if (file.exists()) file.lastModified() else dir.lastModified(),
            )
        }.sortedWith { a, b -> compareVersionsDesc(a.version, b.version) }
    }

    /** True when `version` is exactly what the next launch would boot (mirrors getLauncherBundleFilePathLocal). */
    internal fun willBootNextLaunch(context: Context, module: OtaModule, version: String): Boolean {
        if (!module.launcher) return false
        if (OtaPreferences.isSafeModeEnabled(context) || OtaPreferences.isLocalSafeModeEnabled(context) ||
            OtaPreferences.isModuleSafeModeEnabled(context, module.configKey)) return false
        val diskVersion = getJsBundleDiskVersion(context, module) ?: return false
        if (diskVersion != version) return false
        val assetVersion = getJsBundleAssetVersion(module) ?: return true
        return listOf(diskVersion, assetVersion).semanticMax() == diskVersion && diskVersion != assetVersion
    }

    private fun compareVersionsDesc(a: String, b: String): Int {
        val max = listOf(a, b).semanticMax()
        return when {
            a == b -> 0
            max == a -> -1
            else -> 1
        }
    }

    /**
     * The version of the bundle shipped inside this binary. The launcher's flat
     * asset carries `moduleVersion` from app.json; non-launcher modules ship no
     * asset at all (disk/remote only).
     */
    internal fun getJsBundleAssetVersion(module: OtaModule): String? {
        return if (module.launcher) module.moduleVersion else null
    }

    internal fun getJsBundleDiskVersion(context: Context, module: OtaModule): String? {
        return try {
            val versionFolders = File(getFilesDir(context), module.modulePath).list()
            val validVersions = versionFolders?.filter { version ->
                val bundleFile = buildJsBundleFilePath(context, module, version)
                bundleFile.exists() && BundleDownloader.hasMarker(bundleFile)
            } ?: emptyList()
            validVersions.semanticMax()
        } catch (e: Exception) {
            OtaLog.e("OtaBundleManager::getJsBundleDiskVersion error: ${e.message}")
            null
        }
    }

    private fun buildJsBundleFilePath(context: Context, module: OtaModule, version: String): File {
        return File(getFilesDir(context), "${module.modulePath}/$version/${getBundleName()}")
    }

    private fun getFilesDir(context: Context): File = context.filesDir

    private fun getTemporaryDir(context: Context): File = context.cacheDir

    internal fun getBundleName(): String = "index.android.bundle"
}
