import AVFoundation
import Contacts
import CoreBluetooth
import CoreLocation
import Photos
import UIKit

public final class SettingAPI: DMPContainerApi {
    private static let GET_SETTING = "getSetting"
    private static let OPEN_SETTING = "openSetting"
    private static let AUTHORIZE = "authorize"
    private static let NOTIFY_MODULE = "dsh-notify"
    private static let EVENT_GET_SUBSCRIBE_STATE = "getSubscribeState"

    private static let scopes = [
        "scope.camera",
        "scope.record",
        "scope.userLocation",
        "scope.writePhotosAlbum",
        "scope.addPhoneContact",
        "scope.bluetooth",
    ]

    @BridgeMethod(GET_SETTING)
    var getSetting: DMPBridgeMethodHandler = { _, env, callback in
        SettingAPI.emitSettingResult(apiName: SettingAPI.GET_SETTING, env: env, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(OPEN_SETTING)
    var openSetting: DMPBridgeMethodHandler = { _, env, callback in
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            DMPContainerApi.invokeFailure(callback: callback, param: nil,
                                          errMsg: "openSetting:fail unavailable",
                                          completeCarriesResult: true)
            return DMPAsyncResult()
        }
        DispatchQueue.main.async {
            let observerBox = DMPSettingsObserverBox()
            observerBox.token = NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { _ in
                if let observer = observerBox.token { NotificationCenter.default.removeObserver(observer) }
                observerBox.token = nil
                SettingAPI.emitSettingResult(apiName: SettingAPI.OPEN_SETTING, env: env, callback: callback)
            }
            UIApplication.shared.open(url, options: [:]) { opened in
                if !opened {
                    if let observer = observerBox.token { NotificationCenter.default.removeObserver(observer) }
                    observerBox.token = nil
                    DMPContainerApi.invokeFailure(callback: callback, param: nil,
                                                  errMsg: "openSetting:fail unavailable",
                                                  completeCarriesResult: true)
                }
            }
        }
        return DMPAsyncResult()
    }

    @BridgeMethod(AUTHORIZE)
    var authorize: DMPBridgeMethodHandler = { param, _, callback in
        guard let scope = param.getMap().getString(key: "scope"), SettingAPI.scopes.contains(scope) else {
            DMPContainerApi.invokeFailure(callback: callback, param: nil,
                                          errMsg: "authorize:fail invalid scope",
                                          completeCarriesResult: true)
            return DMPAsyncResult()
        }
        SettingAuthorizationRequest.request(scope: scope) { granted in
            let result = DMPMap(["errMsg": granted ? "authorize:ok" : "authorize:fail auth deny"])
            if granted {
                DMPContainerApi.invokeSuccess(callback: callback, param: result, completeCarriesResult: true)
            } else {
                DMPContainerApi.invokeFailure(callback: callback, param: result,
                                              errMsg: "authorize:fail auth deny",
                                              completeCarriesResult: true)
            }
        }
        return DMPAsyncResult()
    }

    fileprivate static func isGranted(_ scope: String) -> Bool {
        switch scope {
        case "scope.camera":
            return AVCaptureDevice.authorizationStatus(for: .video) == .authorized
        case "scope.record":
            return AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
        case "scope.userLocation":
            return [.authorizedAlways, .authorizedWhenInUse].contains(CLLocationManager.authorizationStatus())
        case "scope.writePhotosAlbum":
            let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
            return status == .authorized || status == .limited
        case "scope.addPhoneContact":
            return CNContactStore.authorizationStatus(for: .contacts) == .authorized
        case "scope.bluetooth":
            return CBManager.authorization == .allowedAlways
        default:
            return false
        }
    }

    private static func settingResult(apiName: String) -> DMPMap {
        var authSetting: [String: Bool] = [:]
        scopes.forEach { authSetting[$0] = isGranted($0) }
        return DMPMap(["authSetting": authSetting, "errMsg": "\(apiName):ok"])
    }

    /// `getSetting` / `openSetting` 的结果里带上 `subscriptionsSetting`（微信兼容形状）：
    /// `{mainSwitch, itemSettings:{tplId:'accept'|'reject'}}`。状态属于宿主，框架只在
    /// 宿主已注册 `dsh-notify` 扩展时读取；无宿主时给默认值（总开关开、无模板记录），
    /// 保证独立运行 Dimina 时 getSetting 仍然可用。
    private static func emitSettingResult(
        apiName: String,
        env: DMPBridgeEnv,
        callback: DMPBridgeCallback?
    ) {
        let result = settingResult(apiName: apiName)
        guard let app = DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
              let handler = app.container?.extModules[NOTIFY_MODULE] else {
            result.set("subscriptionsSetting", defaultSubscriptionsSetting())
            DMPContainerApi.invokeSuccess(callback: callback, param: result, completeCarriesResult: true)
            return
        }

        // 宿主可能同步回调；不论同步/异步，终态回调只发一次。
        let guardFlag = DMPSettingOnceGuard()
        let extCallback = DMPExtCallback(
            onSuccess: { state in
                guard guardFlag.claim() else { return }
                result.set("subscriptionsSetting", normalizeSubscriptionsSetting(state))
                DMPContainerApi.invokeSuccess(callback: callback, param: result, completeCarriesResult: true)
            },
            onFail: { error in
                guard guardFlag.claim() else { return }
                DMPLogger.debug("getSubscribeState failed: \(error.toJsonString())")
                result.set("subscriptionsSetting", defaultSubscriptionsSetting())
                DMPContainerApi.invokeSuccess(callback: callback, param: result, completeCarriesResult: true)
            }
        )
        // appId 用于宿主按小程序取模板级 itemSettings；总开关 mainSwitch 是用户级。
        _ = handler(EVENT_GET_SUBSCRIBE_STATE, DMPMap(["appId": env.appId]), extCallback)
    }

    private static func defaultSubscriptionsSetting() -> [String: Any] {
        return ["mainSwitch": true, "itemSettings": [String: String]()]
    }

    private static func normalizeSubscriptionsSetting(_ state: DMPMap) -> [String: Any] {
        // 宿主按契约直接返回 {mainSwitch,itemSettings}；兼容包了一层 subscriptionsSetting 的实现。
        let source = (state.get("subscriptionsSetting") as? [String: Any]) ?? state.toDictionary()
        let mainSwitch = (source["mainSwitch"] as? Bool)
            ?? ((source["mainSwitch"] as? NSNumber)?.boolValue)
            ?? true
        let itemSettings = (source["itemSettings"] as? [String: Any]) ?? [:]
        return ["mainSwitch": mainSwitch, "itemSettings": itemSettings]
    }
}

/// 宿主回调可能同步/异步到达，用一次性标记保证终态回调只发一次。
private final class DMPSettingOnceGuard: @unchecked Sendable {
    private let lock = NSLock()
    private var resolved = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !resolved else { return false }
        resolved = true
        return true
    }
}

private final class DMPSettingsObserverBox: @unchecked Sendable {
    var token: NSObjectProtocol?
}

private enum SettingAuthorizationRequest {
    private static let lock = NSLock()
    private static var retained: [UUID: AnyObject] = [:]

    static func request(scope: String, completion: @escaping (Bool) -> Void) {
        switch scope {
        case "scope.camera":
            AVCaptureDevice.requestAccess(for: .video) { granted in DispatchQueue.main.async { completion(granted) } }
        case "scope.record":
            AVCaptureDevice.requestAccess(for: .audio) { granted in DispatchQueue.main.async { completion(granted) } }
        case "scope.writePhotosAlbum":
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                DispatchQueue.main.async { completion(status == .authorized || status == .limited) }
            }
        case "scope.addPhoneContact":
            CNContactStore().requestAccess(for: .contacts) { granted, _ in
                DispatchQueue.main.async { completion(granted) }
            }
        case "scope.userLocation":
            retain(LocationAuthorizationRequester()) { requester, finish in
                requester.start { granted in finish(); completion(granted) }
            }
        case "scope.bluetooth":
            retain(BluetoothAuthorizationRequester()) { requester, finish in
                requester.start { granted in finish(); completion(granted) }
            }
        default:
            completion(false)
        }
    }

    private static func retain<T: AnyObject>(_ object: T, start: (T, @escaping () -> Void) -> Void) {
        let id = UUID()
        lock.lock(); retained[id] = object; lock.unlock()
        start(object) {
            lock.lock(); retained.removeValue(forKey: id); lock.unlock()
        }
    }
}

private final class LocationAuthorizationRequester: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var completion: ((Bool) -> Void)?

    func start(completion: @escaping (Bool) -> Void) {
        self.completion = completion
        manager.delegate = self
        manager.requestWhenInUseAuthorization()
        finishIfDetermined()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { finishIfDetermined() }

    private func finishIfDetermined() {
        let status = manager.authorizationStatus
        guard status != .notDetermined else { return }
        let result = status == .authorizedAlways || status == .authorizedWhenInUse
        let callback = completion
        completion = nil
        DispatchQueue.main.async { callback?(result) }
    }
}

private final class BluetoothAuthorizationRequester: NSObject, CBCentralManagerDelegate {
    private var manager: CBCentralManager?
    private var completion: ((Bool) -> Void)?

    func start(completion: @escaping (Bool) -> Void) {
        self.completion = completion
        manager = CBCentralManager(delegate: self, queue: .main)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard CBManager.authorization != .notDetermined else { return }
        let callback = completion
        completion = nil
        callback?(CBManager.authorization == .allowedAlways)
    }
}
