#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

// Generated Swift header for this pod's module (CashifyOtaUpdate). Angle-bracket
// form is canonical under use_frameworks!; quoted form covers static-library builds.
#if __has_include(<CashifyOtaUpdate/CashifyOtaUpdate-Swift.h>)
#import <CashifyOtaUpdate/CashifyOtaUpdate-Swift.h>
#else
#import "CashifyOtaUpdate-Swift.h"
#endif

// JS bridge, exposed as `NativeModules.CashifyOtaUpdate` (legacy paper module —
// do NOT add codegen). Mirrors the Android OtaUpdateModule. Events:
// CashifyOtaLog, CashifyOtaProgress.
@interface OtaUpdateModule : RCTEventEmitter <RCTBridgeModule>
@end

@implementation OtaUpdateModule

RCT_EXPORT_MODULE(CashifyOtaUpdate);

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

- (NSArray<NSString *> *)supportedEvents
{
  return @[@"CashifyOtaLog", @"CashifyOtaProgress"];
}

- (void)startObserving
{
  __weak OtaUpdateModule *weakSelf = self;
  OtaEvents.sink = ^(NSString *name, NSDictionary<NSString *, id> *body) {
    [weakSelf sendEventWithName:name body:body];
  };
}

- (void)stopObserving
{
  OtaEvents.sink = nil;
}

// OTA bundle version when a downloaded bundle booted this session, else the
// host app's versionName (the shipped asset bundle).
RCT_EXPORT_BLOCKING_SYNCHRONOUS_METHOD(getOtaBundleVersion)
{
  return [OtaBundleManager currentBundleVersion];
}

RCT_EXPORT_METHOD(getFileSystemURL:(NSString *)moduleName
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [OtaBundleManager fileSystemURLForModule:moduleName
                                completion:^(NSString *_Nullable url, NSError *_Nullable error) {
    if (url != nil) {
      resolve(url);
    } else {
      reject(@"CashifyOtaUpdate", error.localizedDescription ?: @"Error getting file system URL", error);
    }
  }];
}

RCT_EXPORT_METHOD(getOtaStatus:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [OtaDebugBridge statusWithCompletion:^(NSDictionary *status) {
    resolve(status);
  }];
}

RCT_EXPORT_METHOD(getOtaLogs:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve([OtaDebugBridge logs]);
}

RCT_EXPORT_METHOD(clearOtaLogs:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [OtaDebugBridge clearLogs];
  resolve([NSNull null]);
}

// Resolves the human-readable summary String (e.g. "CashifyOps: downloaded 8.2.0").
RCT_EXPORT_METHOD(checkForUpdates:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [OtaDebugBridge checkForUpdatesWithCompletion:^(NSString *summary) {
    resolve(summary);
  }];
}

RCT_EXPORT_METHOD(deleteDownloadedBundles:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [OtaDebugBridge deleteDownloadedBundles];
  resolve([NSNull null]);
}

RCT_EXPORT_METHOD(setLocalSafeMode:(BOOL)enabled
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [OtaDebugBridge setLocalSafeMode:enabled];
  resolve([NSNull null]);
}

@end
