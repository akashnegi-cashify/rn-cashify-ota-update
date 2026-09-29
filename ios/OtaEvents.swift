import Foundation

/// JS event fan-out. The ObjC bridge module installs `sink` in `startObserving`
/// and clears it in `stopObserving`, so nothing here imports React.
@objc public class OtaEvents: NSObject {
  public static let eventLog = "CashifyOtaLog"
  public static let eventProgress = "CashifyOtaProgress"

  private static let sinkLock = NSLock()
  private static var _sink: ((String, [String: Any]) -> Void)?

  @objc public static var sink: ((String, [String: Any]) -> Void)? {
    get { sinkLock.lock(); defer { sinkLock.unlock() }; return _sink }
    set { sinkLock.lock(); _sink = newValue; sinkLock.unlock() }
  }

  static func emitLog(_ entry: OtaLogEntry) {
    sink?(eventLog, entry.dictionary)
  }

  static func emitProgress(moduleName: String, bytesRead: Int64, totalBytes: Int64, done: Bool) {
    sink?(eventProgress, [
      "moduleName": moduleName,
      "bytesRead": NSNumber(value: bytesRead),
      "totalBytes": NSNumber(value: totalBytes),
      "done": done,
    ])
  }
}
