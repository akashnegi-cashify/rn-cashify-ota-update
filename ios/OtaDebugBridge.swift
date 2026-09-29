import Foundation

/// ObjC-visible, completion-based entry points for the debug-screen bridge
/// methods in OtaUpdateModule.m, so no Swift file needs to import React.
@objc public class OtaDebugBridge: NSObject {

  @objc public static func status(completion: @escaping (NSDictionary) -> Void) {
    Task.detached(priority: .userInitiated) {
      completion(OtaStatus.snapshot() as NSDictionary)
    }
  }

  @objc public static func logs() -> NSArray {
    Log.entries().map { $0.dictionary } as NSArray
  }

  @objc public static func clearLogs() {
    Log.clear()
    Log.d("OtaUpdateModule::logs cleared from debug screen")
  }

  @objc public static func checkForUpdates(completion: @escaping (NSString) -> Void) {
    Log.d("OtaUpdateModule::checkForUpdates requested from debug screen")
    Task.detached(priority: .userInitiated) {
      let summary = await OtaModuleManager.shared.loadBundles(forceFetch: true)
      completion(summary as NSString)
    }
  }

  @objc public static func deleteDownloadedBundles() {
    OtaModuleManager.shared.initModules()
    Log.d("OtaUpdateModule::deleteDownloadedBundles requested from debug screen")
    for module in OtaModuleManager.shared.allModules() {
      OtaBundleManager.cleanupModuleBundles(module: module)
    }
    OtaBundleManager.cleanupTemporaryBundles()
  }

  @objc public static func setLocalSafeMode(_ enabled: Bool) {
    OtaPreferences.setLocalSafeModeEnabled(enabled)
    Log.d("OtaUpdateModule::local safe-mode override set to \(enabled)")
  }
}
