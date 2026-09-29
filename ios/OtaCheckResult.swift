import Foundation

/// Outcome of one module's check; `description` is the JS-visible result string.
enum OtaCheckResult {
  case downloaded(String)
  case upToDate
  case skipped(String)
  case failed(String)

  var description: String {
    switch self {
    case .downloaded(let v): return "downloaded \(v)"
    case .upToDate: return "up to date"
    case .skipped(let r): return "skipped: \(r)"
    case .failed(let m): return "error: \(m)"
    }
  }
}

struct OtaBundleInfo {
  let version: String
  let path: String
  let sizeBytes: Int64
  let valid: Bool
  let modifiedAt: Int64
}
