export interface OtaLogEntry {
  /** Epoch milliseconds. */
  ts: number;
  level: 'D' | 'W' | 'E';
  message: string;
}

export interface OtaProgress {
  moduleName: string;
  /** Compressed bytes received so far. iOS reports 0 (no streaming). */
  bytesRead: number;
  /** Content-Length, or -1 when unknown. */
  totalBytes: number;
  done: boolean;
}

export interface OtaBundleInfo {
  version: string;
  path: string;
  sizeBytes: number;
  /** File exists and carries the END_OF_FILE_MARKER. */
  valid: boolean;
  modifiedAt: number;
  /** Exactly what the next cold launch would boot (launcher module, no safe mode, newest valid, > moduleVersion). */
  willBootNextLaunch: boolean;
}

export interface OtaStatus {
  platform: 'android' | 'ios';
  /** Debuggable builds never load a disk bundle at launch. */
  debuggable: boolean;
  installedAppVersion: string;
  bootedBundleVersion: string;
  bootedFromDisk: boolean;
  module: {
    moduleName: string;
    modulePath: string;
    configKey: string;
    moduleVersion: string;
    otaUpdates: boolean;
  } | null;
  remote: {
    bundleUrl: string;
    latestVersion: string;
    safeMode: boolean;
    moduleSafeMode: boolean;
    lastFetchStatus: 'success' | 'failure' | 'throttled' | 'no_fetch_yet';
    lastFetchAt: number | null;
    resolvedDownloadUrl: string | null;
  };
  local: {
    safeModePersisted: boolean;
    moduleSafeModePersisted: boolean;
    localSafeModeOverride: boolean;
    lastCheckAt: number | null;
    lastCheckResult: string | null;
    checkInFlight: boolean;
  };
  bundles: OtaBundleInfo[];
}

export type OtaEventMap = {
  CashifyOtaLog: OtaLogEntry;
  CashifyOtaProgress: OtaProgress;
};

export interface CashifyOtaUpdateNativeModule {
  getOtaBundleVersion(): string;
  getFileSystemURL(moduleName: string): Promise<string>;
  getOtaStatus(): Promise<OtaStatus>;
  getOtaLogs(): Promise<OtaLogEntry[]>;
  clearOtaLogs(): Promise<void>;
  /** Forced Remote Config refresh + full check. Resolves with a summary such as `CashifyOps: downloaded 8.2.0`. */
  checkForUpdates(): Promise<string>;
  deleteDownloadedBundles(): Promise<void>;
  setLocalSafeMode(enabled: boolean): Promise<void>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}
