import Foundation

/// Read-only snapshot for the JS debug screen. Field names are the JS contract
/// shared with Android's OtaStatus — keep them identical.
enum OtaStatus {
  static func snapshot() -> [String: Any] {
    OtaModuleManager.shared.initModules()
    let module = OtaModuleManager.shared.launcherModule()
    let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0"
    let bundleUrl = OtaRemoteConfig.cachedString("rn_bundle_url")
    let latestVersion = module.map { OtaRemoteConfig.cachedString("rnb_\($0.configKey)_latest_version") } ?? ""

    var moduleDict: Any = NSNull()
    if let m = module {
      moduleDict = [
        "moduleName": m.moduleName,
        "modulePath": m.modulePath,
        "configKey": m.configKey,
        "moduleVersion": m.moduleVersion,
        "otaUpdates": m.otaUpdates,
      ] as [String: Any]
    }
    var resolved: Any = NSNull()
    if let m = module, !bundleUrl.isEmpty, !latestVersion.isEmpty,
       let url = OtaBundleManager.resolvedDownloadUrl(bundleUrl: bundleUrl, module: m, version: latestVersion) {
      resolved = url
    }
    let bundles: [[String: Any]] = module.map { m in
      OtaBundleManager.listBundles(module: m).map { b -> [String: Any] in
        [
          "version": b.version,
          "path": b.path,
          "sizeBytes": NSNumber(value: b.sizeBytes),
          "valid": b.valid,
          "modifiedAt": NSNumber(value: b.modifiedAt),
          "willBootNextLaunch": OtaBundleManager.willBootNextLaunch(module: m, version: b.version),
        ]
      }
    } ?? []

    let lastFetchAt: Any = OtaRemoteConfig.lastFetchTimeMillis().map { NSNumber(value: $0) } ?? NSNull()
    let lastCheckAt: Any = OtaPreferences.lastCheckAt.map { NSNumber(value: $0) } ?? NSNull()
    let lastCheckResult: Any = OtaPreferences.lastCheckResult ?? NSNull()

    let remote: [String: Any] = [
      "bundleUrl": bundleUrl,
      "latestVersion": latestVersion,
      "safeMode": OtaRemoteConfig.cachedBoolean("rn_enable_safe_mode"),
      "moduleSafeMode": module.map { OtaRemoteConfig.cachedBoolean("rnb_\($0.configKey)_enable_safe_mode") } ?? false,
      "lastFetchStatus": OtaRemoteConfig.lastFetchStatus(),
      "lastFetchAt": lastFetchAt,
      "resolvedDownloadUrl": resolved,
    ]
    let local: [String: Any] = [
      "safeModePersisted": OtaPreferences.isSafeModeEnabled,
      "moduleSafeModePersisted": module.map { OtaPreferences.isModuleSafeModeEnabled($0.configKey) } ?? false,
      "localSafeModeOverride": OtaPreferences.isLocalSafeModeEnabled,
      "lastCheckAt": lastCheckAt,
      "lastCheckResult": lastCheckResult,
      "checkInFlight": OtaModuleManager.shared.checkInFlight,
    ]

    return [
      "platform": "ios",
      "debuggable": isDebugMode(),
      "installedAppVersion": appVersion,
      "bootedBundleVersion": OtaBundleManager.currentBundleVersion(),
      "bootedFromDisk": OtaBundleManager.bootedFromDisk,
      "module": moduleDict,
      "remote": remote,
      "local": local,
      "bundles": bundles,
    ]
  }
}
