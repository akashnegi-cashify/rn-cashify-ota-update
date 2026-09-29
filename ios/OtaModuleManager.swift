import Foundation

/// Parses the module map from the app.json bundle resource (key `otaModules`,
/// falling back to `legoModules` for lego-style apps) and orchestrates the
/// background OTA check for every module with `otaUpdates: true`.
public final class OtaModuleManager {

  public static let shared = OtaModuleManager()

  private(set) var modules: [String: OtaModule] = [:]
  private var initialized = false

  private let lock = NSLock()
  private var bundleVersionMap: [String: String] = [:]
  /// Tail of the check chain; each new pass waits for it before running.
  private var currentCheck: Task<String, Never>?
  /// Passes queued or running. Guarded by `lock`.
  private var pendingChecks = 0

  /// True while any `loadBundles` pass is queued or running.
  public var checkInFlight: Bool {
    lock.lock(); defer { lock.unlock() }
    return pendingChecks > 0
  }

  private init() {}

  /// Synchronous, local-only app.json parse — safe on the launch path. Never
  /// throws; on any failure the module map stays empty and OTA is disabled.
  public func initModules() {
    Log.load()
    guard !initialized else { return }
    initialized = true
    guard let url = Bundle.main.url(forResource: "app", withExtension: "json") else {
      Log.e("OtaModuleManager::init::app.json not found in bundle, OTA disabled")
      return
    }
    do {
      let data = try Data(contentsOf: url)
      guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        Log.e("OtaModuleManager::init::app.json is not an object, OTA disabled")
        return
      }
      let modulesJson = (json["otaModules"] as? [String: [String: Any]])
        ?? (json["legoModules"] as? [String: [String: Any]])
      guard let modulesJson else {
        Log.e("OtaModuleManager::init::no otaModules/legoModules in app.json, OTA disabled")
        return
      }
      var parsed: [String: OtaModule] = [:]
      for (key, moduleJson) in modulesJson {
        guard
          let moduleName = moduleJson["moduleName"] as? String,
          let modulePath = moduleJson["modulePath"] as? String,
          let configKey = moduleJson["configKey"] as? String,
          let moduleVersion = moduleJson["moduleVersion"] as? String
        else {
          Log.e("OtaModuleManager::init::invalid module entry: \(key)")
          continue
        }
        parsed[key] = OtaModule(
          moduleName: moduleName,
          modulePath: modulePath,
          configKey: configKey,
          scheme: moduleJson["scheme"] as? String ?? "",
          moduleVersion: moduleVersion,
          bundlePriority: moduleJson["bundlePriority"] as? Int ?? Int.max,
          otaUpdates: moduleJson["otaUpdates"] as? Bool ?? false,
          launcher: moduleJson["launcher"] as? Bool ?? false
        )
      }
      modules = parsed
      Log.d("OtaModuleManager::init::modules: \(modules.keys.joined(separator: ","))")
    } catch {
      Log.e("OtaModuleManager::init failed, OTA disabled: \(error.localizedDescription)")
      modules = [:]
    }
  }

  /// Fire-and-forget background check: temp cleanup -> Remote Config -> global
  /// then per-module safe-mode kill switches -> per-module download-if-needed +
  /// stale/rollback cleanup. Downloaded bundles are picked up on the NEXT launch.
  public func loadBundlesAsync() {
    Task.detached(priority: .background) {
      _ = await self.loadBundles()
    }
  }

  /// One full check. Serialised like Android: a concurrent caller waits for the
  /// running pass, then runs its OWN pass with its own `forceFetch`. Every pass
  /// records the last check time + summary and returns its summary string.
  public func loadBundles(forceFetch: Bool = false) async -> String {
    let task = claimCheck(forceFetch: forceFetch)
    let summary = await task.value
    releaseCheck()
    OtaPreferences.setLastCheck(at: Int64(Date().timeIntervalSince1970 * 1000), result: summary)
    Log.d("OtaModuleManager::check result: \(summary)")
    return summary
  }

  // Synchronous so NSLock is never held across (or called from) an await.
  private func claimCheck(forceFetch: Bool) -> Task<String, Never> {
    lock.lock()
    defer { lock.unlock() }
    let previous = currentCheck
    let task = Task<String, Never> {
      _ = await previous?.value
      return await self.runCheck(forceFetch: forceFetch)
    }
    currentCheck = task
    pendingChecks += 1
    return task
  }

  private func releaseCheck() {
    lock.lock()
    defer { lock.unlock() }
    pendingChecks -= 1
    if pendingChecks == 0 { currentCheck = nil }
  }

  private func runCheck(forceFetch: Bool) async -> String {
    initModules()
    let otaModules = modules.values.filter { $0.otaUpdates }
    guard !otaModules.isEmpty else {
      Log.d("OtaModuleManager::no OTA modules configured, skipping")
      return "skipped: no OTA modules configured"
    }

    OtaBundleManager.cleanupTemporaryBundles()

    if forceFetch, !(await OtaRemoteConfig.refresh()) {
      Log.w("OtaModuleManager::forced Remote Config refresh failed, using cached values")
    }

    // Global kill switch: wipes EVERY module and stops the whole check.
    if await OtaRemoteConfig.getEnableSafeMode() {
      Log.d("OtaModuleManager::global safe mode enabled, clearing all module bundles")
      for module in otaModules {
        OtaBundleManager.cleanupModuleBundles(module: module)
      }
      // Persisted locally so the NEXT launch stays on the asset bundle even offline.
      OtaPreferences.setSafeModeEnabled(true)
      return "skipped: global safe mode enabled"
    }
    OtaPreferences.setSafeModeEnabled(false)

    var results: [String] = []
    let sortedModules = otaModules.sorted {
      ($0.bundlePriority, $0.moduleName) < ($1.bundlePriority, $1.moduleName)
    }
    for module in sortedModules {
      // Per-module kill switch: wipes ONLY this module; others continue.
      if await OtaRemoteConfig.getModuleEnableSafeMode(module: module) {
        Log.d("OtaModuleManager::module safe mode enabled for \(module.moduleName), clearing its bundles")
        OtaBundleManager.cleanupModuleBundles(module: module)
        OtaPreferences.setModuleSafeModeEnabled(module.configKey, true)
        results.append("\(module.moduleName): skipped: module safe mode enabled")
        continue
      }
      OtaPreferences.setModuleSafeModeEnabled(module.configKey, false)

      Log.d("OtaModuleManager::checking module: \(module.moduleName), priority: \(module.bundlePriority)")
      let result = await OtaBundleManager.downloadBundleIfNeeded(module: module)
      await OtaBundleManager.cleanupStaleBundles(module: module)
      results.append("\(module.moduleName): \(result.description)")
    }
    return results.joined(separator: "; ")
  }

  func allModules() -> [OtaModule] {
    Array(modules.values)
  }

  func launcherModule() -> OtaModule? {
    modules.values.first { $0.launcher }
  }

  func getModuleByName(_ moduleName: String) -> OtaModule? {
    modules[moduleName]
  }

  func getBundleVersion(_ moduleName: String) -> String? {
    lock.lock()
    defer { lock.unlock() }
    return bundleVersionMap[moduleName]
  }

  func setBundleVersion(moduleName: String, version: String) {
    lock.lock()
    defer { lock.unlock() }
    bundleVersionMap[moduleName] = version
  }
}
