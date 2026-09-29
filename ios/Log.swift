import Foundation
import os

/// One captured log line. `level` is "D", "W" or "E".
struct OtaLogEntry {
  let ts: Int64
  let level: String
  let message: String

  var dictionary: [String: Any] { ["ts": NSNumber(value: ts), "level": level, "message": message] }

  init(ts: Int64, level: String, message: String) {
    self.ts = ts; self.level = level; self.message = message
  }

  init?(json: [String: Any]) {
    guard let ts = (json["ts"] as? NSNumber)?.int64Value,
          let level = json["level"] as? String,
          let message = json["message"] as? String else { return nil }
    self.init(ts: ts, level: level, message: message)
  }

  /// One JSONL line (with trailing newline), or nil if serialisation fails.
  var jsonLine: String? {
    guard JSONSerialization.isValidJSONObject(dictionary),
          let data = try? JSONSerialization.data(withJSONObject: dictionary),
          let line = String(data: data, encoding: .utf8) else { return nil }
    return line + "\n"
  }
}

/// OTA logging with the same "CashifyOTA" tag as Android, via os_log so lines are
/// visible in Console.app / `log stream --predicate 'eventMessage CONTAINS "CashifyOTA"'`
/// even in Release builds. Every call also lands in an in-memory ring buffer
/// (`maxEntries`), an append-only JSONL file under
/// Application Support/cashify_ota/logs.jsonl (survives relaunch; every
/// 2 * maxEntries appends the file is rewritten from the buffer instead, capping
/// its growth), and — when JS is listening — a `CashifyOtaLog` event. Never throws.
enum Log {
  static let maxEntries = 300

  private static let logger = Logger(
    subsystem: Bundle.main.bundleIdentifier ?? "CashifyOps",
    category: "CashifyOTA"
  )

  // Guards buffer, loaded and appendsSinceRewrite. File-queue submissions are
  // made while holding it, so the (serial) queue sees writes in buffer order.
  private static let lock = NSLock()
  private static var buffer: [OtaLogEntry] = []
  private static var loaded = false
  private static var appendsSinceRewrite = 0
  private static let queue = DispatchQueue(label: "in.cashify.ota.log", qos: .utility)

  private static var fileURL: URL? {
    guard let dir = try? FileManager.default
      .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
      .appendingPathComponent("cashify_ota") else { return nil }
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir.appendingPathComponent("logs.jsonl")
  }

  /// Loads the persisted tail. Idempotent; called from `OtaModuleManager.initModules`
  /// (and lazily by the first log call, so early lines are never lost or duplicated).
  static func load() {
    lock.lock(); defer { lock.unlock() }
    loadLocked()
  }

  static func d(_ message: String) {
    logger.log("CashifyOTA::\(message, privacy: .public)")
    record("D", message)
  }

  static func w(_ message: String) {
    logger.warning("CashifyOTA::WARN::\(message, privacy: .public)")
    record("W", message)
  }

  static func e(_ message: String) {
    logger.error("CashifyOTA::ERROR::\(message, privacy: .public)")
    record("E", message)
  }

  static func entries() -> [OtaLogEntry] {
    lock.lock(); defer { lock.unlock() }
    loadLocked()
    return buffer
  }

  static func clear() {
    lock.lock(); defer { lock.unlock() }
    loaded = true
    buffer.removeAll()
    appendsSinceRewrite = 0
    queue.async {
      if let url = fileURL { try? "".write(to: url, atomically: true, encoding: .utf8) }
    }
  }

  // MARK: - Private (callers hold `lock` for every *Locked function)

  private static func loadLocked() {
    guard !loaded else { return }
    loaded = true
    guard let url = fileURL, let text = try? String(contentsOf: url, encoding: .utf8) else { return }
    let lines = text.split(separator: "\n").map(String.init)
    let tail = lines.suffix(maxEntries)
    buffer = tail.compactMap { line in
      guard let data = line.data(using: .utf8),
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
      return OtaLogEntry(json: json)
    }
    if tail.count != lines.count { submitRewriteLocked(buffer) }
  }

  private static func record(_ level: String, _ message: String) {
    let entry = OtaLogEntry(ts: Int64(Date().timeIntervalSince1970 * 1000), level: level, message: message)
    lock.lock()
    loadLocked()
    buffer.append(entry)
    if buffer.count > maxEntries { buffer.removeFirst(buffer.count - maxEntries) }
    appendsSinceRewrite += 1
    if appendsSinceRewrite >= 2 * maxEntries {
      appendsSinceRewrite = 0
      submitRewriteLocked(buffer)
    } else {
      queue.async { append(entry) }
    }
    lock.unlock()
    OtaEvents.emitLog(entry)
  }

  /// Runs on `queue`.
  private static func append(_ entry: OtaLogEntry) {
    guard let url = fileURL,
          let line = entry.jsonLine,
          let data = line.data(using: .utf8) else { return }
    if let handle = try? FileHandle(forWritingTo: url) {
      defer { try? handle.close() }
      _ = try? handle.seekToEnd()
      try? handle.write(contentsOf: data)
    } else {
      try? data.write(to: url, options: .atomic)
    }
  }

  private static func submitRewriteLocked(_ entries: [OtaLogEntry]) {
    queue.async {
      guard let url = fileURL else { return }
      let text = entries.compactMap { $0.jsonLine }.joined()
      try? text.write(to: url, atomically: true, encoding: .utf8)
    }
  }
}
