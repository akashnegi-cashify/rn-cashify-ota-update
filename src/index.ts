import {NativeEventEmitter, NativeModules, Platform} from 'react-native';

import type {CashifyOtaUpdateNativeModule, OtaEventMap} from './types';

export type {
  CashifyOtaUpdateNativeModule,
  OtaBundleInfo,
  OtaEventMap,
  OtaLogEntry,
  OtaProgress,
  OtaStatus,
} from './types';

const LINKING_ERROR =
  "The package 'rn-cashify-ota-update' doesn't seem to be linked. Make sure: \n\n" +
  Platform.select({ios: "- You have run 'pod install'\n", default: ''}) +
  '- You rebuilt the app after installing the package\n';

const nativeModule: CashifyOtaUpdateNativeModule | undefined =
  NativeModules.CashifyOtaUpdate;

const proxy = new Proxy(
  {},
  {
    get(): never {
      throw new Error(LINKING_ERROR);
    },
  },
) as CashifyOtaUpdateNativeModule;

const safeModule: CashifyOtaUpdateNativeModule = nativeModule ?? proxy;

const emitter = nativeModule ? new NativeEventEmitter(nativeModule as never) : undefined;

/** True when the native module is linked into this binary. */
export const isOtaUpdateAvailable = (): boolean => nativeModule != null;

/**
 * Version of the JS bundle this session booted with — the OTA version when a
 * downloaded bundle loaded, else the app versionName. Synchronous.
 */
export const getOtaBundleVersion = (): string => safeModule.getOtaBundleVersion();

/**
 * `file://` URL of a configured OTA module's bundle (disk bundle if a newer one
 * is installed, shipped asset otherwise). For JS-side loaders of non-launcher
 * modules, and debugging.
 */
export const getFileSystemURL = (moduleName: string): Promise<string> =>
  safeModule.getFileSystemURL(moduleName);

// --- Debug API (OTA Status screen) -----------------------------------------

/** Local-only snapshot: booted/installed versions, cached Remote Config, disk bundles, last check. */
export const getOtaStatus = () => safeModule.getOtaStatus();

/** Persisted ring buffer (last 300 lines, oldest first) of every `CashifyOTA` log line. */
export const getOtaLogs = () => safeModule.getOtaLogs();

export const clearOtaLogs = () => safeModule.clearOtaLogs();

/** Forces a Remote Config refresh and runs the same check as launch. Serialised natively. */
export const checkForUpdates = () => safeModule.checkForUpdates();

/** Deletes every downloaded bundle; the next launch boots the shipped asset. */
export const deleteDownloadedBundles = () => safeModule.deleteDownloadedBundles();

/** Debug override that pins the next launch to the shipped bundle. Never touched by Remote Config. */
export const setLocalSafeMode = (enabled: boolean) => safeModule.setLocalSafeMode(enabled);

/** Subscribe to a native OTA event. Returns the unsubscribe function. No-op when unlinked. */
export function addOtaListener<K extends keyof OtaEventMap>(
  event: K,
  listener: (payload: OtaEventMap[K]) => void,
): () => void {
  if (!emitter) return () => undefined;
  const subscription = emitter.addListener(event, listener as (payload: unknown) => void);
  return () => subscription.remove();
}
