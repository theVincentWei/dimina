//
//  SensorAPI.swift
//  dimina
//
//  Created by DosLin on 2026/11/17.
//

import CoreLocation
import CoreMotion
import Foundation

/**
 * Device - Sensor API（加速度计/罗盘/陀螺仪/设备方向，四通道 16 个 wire API）。
 *
 * wire 契约（与 service 层、Android 容器三方一致，见 SensorApi.kt / sensor/shared.js）：
 *   start/stop    → params.success/fail/complete 是一次性回执 id，处理完点 success
 *   onXxxChange   → params.success 是常驻 keep 回调 id，数据到达时按该 id 回点火
 *                   （同 NetworkTypeAPI 的订阅表回点火形状），首个 keep id 登记即隐式
 *                   激活采样（微信语义：on 无需先 start）；同一 keep id 重复 on 幂等
 *                   登记，不重复激活
 *   offXxxChange  → params.success 有值摘单个 id、无值摘全部；全部摘除后停源省电，
 *                   仍可 start/on 重启；stop 只停源，keep 订阅保留（静默）
 *   start({interval}) → 'game'=20ms / 'ui'=60ms / 'normal'=200ms 节流，未知值落 normal
 *
 * 单位与微信/Android 对齐：
 *   加速度计 {x,y,z} m/s² —— CMAcceleration 单位是 g，×9.80665 换算（Android
 *                   TYPE_ACCELEROMETER 含重力直报 m/s²，CMDeviceMotion.acceleration
 *                   同样含重力，语义一致）
 *   陀螺仪   {x,y,z} rad/s —— CMRotationRate 直报，不换算
 *   设备方向 {alpha,beta,gamma} 角度 —— CMAttitude yaw/pitch/roll 弧度转角度；
 *                   alpha=(yaw°+360)%360 归一到 0-360，对齐 Android rotation vector
 *                   的北向方位角（yaw 以磁北为零点），beta=pitch、gamma=roll 带符号
 *   罗盘     {direction,accuracy} —— CLLocationManager.heading：direction=magneticHeading
 *                   （0-360，正北 0），accuracy=headingAccuracy，负值=不可用，按
 *                   Android SENSOR_STATUS_UNRELIABLE（-1）的数值语义报 -1
 *
 * owner 隔离：静态订阅表跨小程序栈共享，状态按 (appId, channel) 隔离；四通道互不
 * 拉起——加速度计/陀螺仪/设备方向三个运动通道共用每 app 一条 CMMotionManager 融合流
 * （任一监听时才启动，更新间隔取活动通道最小值，fire 侧按通道 listening 过滤），
 * 罗盘独立走 CLLocationManager，只在罗盘通道激活时拉起。数据回火走 bridge 分发的
 * triggerCallback 闭包，app 销毁后闭包内 weak container 探活自动失效。
 *
 * 资源清理：小程序重启（reload）会再次走 DMPContainerApi.create → 本 init，按 appId
 * 清场上一代订阅与采样源；销毁/退栈路径约定 clearApp(appId)（与 NetworkTypeAPI 同
 * 约定，由 DMPApp.destroy/restart 调用）；app 实例释放后 fire 侧弱引用探活失败也会
 * 回收该 app 全部采样源兜底。
 *
 * Author: Doslin
 */
public final class SensorAPI: DMPContainerApi {

    // MARK: - Channel

    private enum Channel: CaseIterable {
        case accelerometer
        case compass
        case gyroscope
        case deviceMotion

        var startApi: String {
            switch self {
            case .accelerometer: return "startAccelerometer"
            case .compass: return "startCompass"
            case .gyroscope: return "startGyroscope"
            case .deviceMotion: return "startDeviceMotionListening"
            }
        }

        var stopApi: String {
            switch self {
            case .accelerometer: return "stopAccelerometer"
            case .compass: return "stopCompass"
            case .gyroscope: return "stopGyroscope"
            case .deviceMotion: return "stopDeviceMotionListening"
            }
        }

        var onApi: String {
            switch self {
            case .accelerometer: return "onAccelerometerChange"
            case .compass: return "onCompassChange"
            case .gyroscope: return "onGyroscopeChange"
            case .deviceMotion: return "onDeviceMotionChange"
            }
        }

        var offApi: String {
            switch self {
            case .accelerometer: return "offAccelerometerChange"
            case .compass: return "offCompassChange"
            case .gyroscope: return "offGyroscopeChange"
            case .deviceMotion: return "offDeviceMotionChange"
            }
        }

        /// 三个运动通道共用 CMMotionManager 融合流；罗盘独立走 CLLocationManager。
        var isMotion: Bool { self != .compass }
    }

    // interval wire 值 → 节流周期 ms（契约钉死：game=20 ui=60 normal=200，未知值落 normal）
    private static let intervalGameMs = 20.0
    private static let intervalUIMs = 60.0
    private static let intervalNormalMs = 200.0

    private static func intervalMsFor(wire: String?) -> Double {
        switch wire {
        case "game": return intervalGameMs
        case "ui": return intervalUIMs
        case "normal": return intervalNormalMs
        default: return intervalNormalMs
        }
    }

    // 单位换算：CMAcceleration 是 g，×9.80665 得 m/s²；CMRotationRate 即 rad/s 不换算
    private static let gravityMs2 = 9.80665
    private static let radiansToDegrees = 180.0 / Double.pi

    private static func sourceKey(appId: String, channel: Channel) -> String {
        return "\(appId)/\(channel)"
    }

    // MARK: - 订阅状态（key = appId/channel，跨小程序栈共享）

    private final class Source {
        let appId: String
        let channel: Channel
        // weak 探活：app 实例释放后 fire 侧回收采样源（兜底，正常清理走 clearApp）
        let hasAppRef: Bool
        weak var app: DMPApp?
        var intervalMs = SensorAPI.intervalNormalMs
        var listening = false
        // key = onXxxChange 的常驻 keep 回调 id；同一函数重复 on 拿同一 id，幂等覆盖
        var callbacks: [String: DMPBridgeCallback] = [:]
        // 回点火节流基准（monotonic uptime ms），照 Android lastFireAt 兜底节流
        var lastFireAtMs: UInt64 = 0

        init(appId: String, app: DMPApp?, channel: Channel) {
            self.appId = appId
            self.channel = channel
            self.app = app
            self.hasAppRef = app != nil
        }
    }

    private static let lock = NSLock()
    private static var sources: [String: Source] = [:]
    // 三个运动通道每 app 共用一条融合流与串行回调队列；全部停听后释放
    private static var motionManagers: [String: CMMotionManager] = [:]
    private static var motionQueues: [String: OperationQueue] = [:]
    // 罗盘每 app 一个 CLLocationManager 会话；只在罗盘通道激活时拉起
    private static var compassSessions: [String: CompassSession] = [:]

    private static func sourceLocked(appId: String, app: DMPApp?, channel: Channel) -> Source {
        lock.lock()
        defer { lock.unlock() }
        let key = sourceKey(appId: appId, channel: channel)
        if let source = sources[key] {
            if let app { source.app = app }
            return source
        }
        let source = Source(appId: appId, app: app, channel: channel)
        sources[key] = source
        return source
    }

    private static func existingSource(appId: String, channel: Channel) -> Source? {
        lock.lock()
        defer { lock.unlock() }
        return sources[sourceKey(appId: appId, channel: channel)]
    }

    // MARK: - 生命周期

    // 重启（reload）路径会再次走 DMPContainerApi.create → 本 init：清场同一 appId
    // 上一代 runtime 的订阅与采样源；首次启动时表为空是 no-op。清理入主队列与
    // handleXxx 的状态变更 FIFO 排队，保证清理晚于旧代变更生效（同 Android mainHandler）。
    public override init(app: DMPApp?) {
        super.init(app: app)
        if let appId = app?.getAppId() {
            SensorAPI.clearApp(appId)
        }
    }

    /// 小程序销毁/退栈清理：停它的全部采样源并清订阅（DMPApp.destroy/restart 约定入口，
    /// 与 NetworkTypeAPI.clearApp 同约定）。
    public static func clearApp(_ appId: String) {
        DispatchQueue.main.async {
            lock.lock()
            let appSources = sources.filter { $0.key.hasPrefix("\(appId)/") }
            appSources.forEach { sources.removeValue(forKey: $0.key) }
            let manager = motionManagers.removeValue(forKey: appId)
            motionQueues.removeValue(forKey: appId)
            let session = compassSessions.removeValue(forKey: appId)
            lock.unlock()

            manager?.stopDeviceMotionUpdates()
            session?.invalidate()
            failPendingReceipts(session: session)
            if !appSources.isEmpty {
                DMPLogger.debug("SensorAPI clearApp \(appId): \(appSources.count) sources torn down")
            }
        }
    }

    /// 状态变更统一挪主线程（CMMotionManager/CLLocationManager 都要求带 RunLoop 的线程），
    /// handleXxx 来自 JS bridge 队列；clearApp 的清理同样入主队列 FIFO。
    private static func mutateOnMain(appId: String, app: DMPApp?, channel: Channel,
                                     _ action: @escaping (Source) -> Void) {
        let source = sourceLocked(appId: appId, app: app, channel: channel)
        DispatchQueue.main.async { action(source) }
    }

    // MARK: - start / stop

    private static func handleStart(appId: String, app: DMPApp?, channel: Channel,
                                    param: DMPBridgeParam, callback: DMPBridgeCallback?) {
        let intervalMs = intervalMsFor(wire: param.getMap().getString(key: "interval"))
        mutateOnMain(appId: appId, app: app, channel: channel) { source in
            source.intervalMs = intervalMs
            source.listening = true
            syncSource(source)
            DMPContainerApi.invokeSuccess(callback: callback, param: DMPMap(["errMsg": "\(channel.startApi):ok"]))
        }
    }

    private static func handleStop(appId: String, app: DMPApp?, channel: Channel,
                                   callback: DMPBridgeCallback?) {
        mutateOnMain(appId: appId, app: app, channel: channel) { source in
            // stop 只停采样源；keep 订阅保留（静默），下次 start / onXxxChange 重启
            source.listening = false
            syncSource(source)
            DMPContainerApi.invokeSuccess(callback: callback, param: DMPMap(["errMsg": "\(channel.stopApi):ok"]))
        }
    }

    // MARK: - on / off（常驻 keep 订阅）

    private static func handleOn(appId: String, app: DMPApp?, channel: Channel,
                                 param: DMPBridgeParam, callback: DMPBridgeCallback?) {
        let data = param.getMap()
        guard let callback,
              let keepId = data.getString(key: "callbackId") ?? data.getString(key: "success"),
              !keepId.isEmpty else {
            // 常驻订阅 wire 上只有 success keep id、没有 fail 回调，缺 id 只能记日志跳过
            DMPLogger.debug("SensorAPI \(channel.onApi): missing persistent success callback id")
            return
        }
        mutateOnMain(appId: appId, app: app, channel: channel) { source in
            // keep id 幂等登记：同一函数重复 on 拿同一 id（service 层契约），不重复激活
            source.callbacks[keepId] = callback
            source.listening = true
            syncSource(source)
        }
    }

    private static func handleOff(appId: String, channel: Channel, param: DMPBridgeParam) {
        let keepId = param.getMap().getString(key: "callbackId")
        mutateOnMain(appId: appId, app: nil, channel: channel) { source in
            if let keepId, !keepId.isEmpty {
                // 带 id：摘单个（未登记过的 id 摘除无副作用）
                source.callbacks.removeValue(forKey: keepId)
            } else {
                // 无参注销摘全部：清掉宿主侧可能残留的订阅（service 层契约）
                source.callbacks.removeAll()
            }
            // 摘完停源省电（照 Android）；start/on 随时可重启
            if source.callbacks.isEmpty {
                source.listening = false
            }
            syncSource(source)
        }
    }

    // MARK: - 采样源同步（主线程）

    private static func syncSource(_ source: Source) {
        if source.channel.isMotion {
            syncMotion(appId: source.appId)
        } else {
            syncCompass(appId: source.appId)
        }
    }

    /// 三个运动通道共用一条 CMMotionManager 融合流：任一监听才启动，更新间隔取活动
    /// 通道最小值；全部停听后停源释放硬件。通道之间互不产出数据——fire 侧按通道
    /// listening 过滤，只开加速度计时陀螺仪/设备方向的样本被丢弃。
    private static func syncMotion(appId: String) {
        let activeChannels = Channel.allCases.filter {
            $0.isMotion && (existingSource(appId: appId, channel: $0)?.listening ?? false)
        }
        guard !activeChannels.isEmpty else {
            lock.lock()
            let manager = motionManagers.removeValue(forKey: appId)
            motionQueues.removeValue(forKey: appId)
            lock.unlock()
            manager?.stopDeviceMotionUpdates()
            return
        }

        let manager = acquireMotionManager(appId: appId)
        guard manager.isDeviceMotionAvailable else {
            DMPLogger.debug("SensorAPI \(appId): device motion unavailable, cannot listen")
            return
        }

        let intervalMs = activeChannels
            .compactMap { existingSource(appId: appId, channel: $0)?.intervalMs }
            .min() ?? intervalNormalMs
        let desiredInterval = intervalMs / 1000.0

        if manager.isDeviceMotionActive {
            // 更新间隔只在启动时生效，interval 档位切换时停了再开
            guard manager.deviceMotionUpdateInterval != desiredInterval else { return }
            manager.stopDeviceMotionUpdates()
        }

        // Android 用 TYPE_ROTATION_VECTOR 算北向方位角；iOS 对应磁北参考系，
        // 个别无磁力计设备降级为任意参考系（alpha 失去北向零点，记日志）
        let referenceFrame: CMAttitudeReferenceFrame = {
            let available = CMMotionManager.availableAttitudeReferenceFrames()
            return available.contains(.xMagneticNorthZVertical)
                ? .xMagneticNorthZVertical
                : .xArbitraryZVertical
        }()
        if referenceFrame == .xArbitraryZVertical {
            DMPLogger.debug("SensorAPI \(appId): magnetic north frame unavailable, deviceMotion alpha loses north origin")
        }

        manager.deviceMotionUpdateInterval = desiredInterval
        manager.startDeviceMotionUpdates(using: referenceFrame, to: motionQueue(appId: appId), withHandler: { motion, error in
            if let error {
                DMPLogger.debug("SensorAPI \(appId) motion error: \(error.localizedDescription)")
                return
            }
            guard let motion else { return }

            // 加速度：含重力 = gravity + userAcceleration（CMDeviceMotion 无 acceleration 成员，单位 g），×9.80665 换算 m/s²（对齐 Android）
            let gravity = SensorAPI.gravityMs2
            SensorAPI.fire(appId: appId, channel: .accelerometer, payload: [
                "x": (motion.gravity.x + motion.userAcceleration.x) * gravity,
                "y": (motion.gravity.y + motion.userAcceleration.y) * gravity,
                "z": (motion.gravity.z + motion.userAcceleration.z) * gravity,
            ])
            // 陀螺仪：CMRotationRate 即 rad/s，直报
            SensorAPI.fire(appId: appId, channel: .gyroscope, payload: [
                "x": motion.rotationRate.x,
                "y": motion.rotationRate.y,
                "z": motion.rotationRate.z,
            ])
            // 设备方向：CMAttitude yaw/pitch/roll 弧度 → 角度；alpha=(yaw°+360)%360
            // 归一到 0-360（对齐 Android rotation vector 北向方位角），beta/gamma 带符号
            let yawDegrees = motion.attitude.yaw * SensorAPI.radiansToDegrees
            let alpha = yawDegrees.truncatingRemainder(dividingBy: 360.0)
            SensorAPI.fire(appId: appId, channel: .deviceMotion, payload: [
                "alpha": alpha < 0 ? alpha + 360.0 : alpha,
                "beta": motion.attitude.pitch * SensorAPI.radiansToDegrees,
                "gamma": motion.attitude.roll * SensorAPI.radiansToDegrees,
            ])
        })
    }

    private static func acquireMotionManager(appId: String) -> CMMotionManager {
        lock.lock()
        defer { lock.unlock() }
        if let manager = motionManagers[appId] { return manager }
        let manager = CMMotionManager()
        motionManagers[appId] = manager
        return manager
    }

    private static func motionQueue(appId: String) -> OperationQueue {
        lock.lock()
        defer { lock.unlock() }
        if let queue = motionQueues[appId] { return queue }
        let queue = OperationQueue()
        queue.name = "com.didi.dimina.sensor.motion.\(appId)"
        queue.maxConcurrentOperationCount = 1
        motionQueues[appId] = queue
        return queue
    }

    // MARK: - 罗盘（CLLocationManager heading，主线程）

    /// 罗盘每 app 一个 CLLocationManager 会话；heading 回调走主线程（CLLocationManager
    /// 要求带 RunLoop 的线程）。只在罗盘通道激活时拉起，与三个运动通道互不干扰。
    private final class CompassSession: NSObject, CLLocationManagerDelegate {
        let appId: String
        let manager = CLLocationManager()
        // startCompass 的一次性回执在授权未决时暂存，授权落定后补点
        var pendingStartReceipts: [DMPBridgeCallback] = []
        var hasRequestedAuthorization = false
        var headingActive = false

        init(appId: String) {
            self.appId = appId
            super.init()
            manager.delegate = self
        }

        func startHeadingIfNeeded() {
            guard !headingActive else { return }
            headingActive = true
            manager.startUpdatingHeading()
        }

        func stopHeading() {
            guard headingActive else { return }
            headingActive = false
            manager.stopUpdatingHeading()
        }

        /// 会话拆除：断开 delegate（CLLocationManager.delegate 强持有，不清会与
        /// session↔manager 形成环，从静态表移除后无法释放）。
        func invalidate() {
            stopHeading()
            manager.delegate = nil
        }

        func requestAuthorizationIfNeeded() {
            guard !hasRequestedAuthorization else { return }
            hasRequestedAuthorization = true
            manager.requestWhenInUseAuthorization()
        }

        func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
            switch manager.authorizationStatus {
            case .authorizedAlways, .authorizedWhenInUse:
                SensorAPI.startHeadingIfCompassListening(appId: appId)
                SensorAPI.flushPendingStartReceipts(appId: appId, granted: true)
            case .notDetermined:
                break
            default: // .denied / .restricted
                stopHeading()
                DMPLogger.debug("SensorAPI \(appId) compass: location authorization denied")
                SensorAPI.flushPendingStartReceipts(appId: appId, granted: false)
            }
        }

        func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
            // direction 0-360 正北 0；accuracy 负值=不可用，按 Android
            // SENSOR_STATUS_UNRELIABLE（-1）的数值语义报 -1
            let accuracy = newHeading.headingAccuracy
            SensorAPI.fire(appId: appId, channel: .compass, payload: [
                "direction": newHeading.magneticHeading,
                "accuracy": accuracy >= 0 ? accuracy : -1.0,
            ])
        }

        func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
            DMPLogger.debug("SensorAPI \(appId) compass error: \(error.localizedDescription)")
        }
    }

    private static func acquireCompassSession(appId: String) -> CompassSession {
        lock.lock()
        defer { lock.unlock() }
        if let session = compassSessions[appId] { return session }
        let session = CompassSession(appId: appId)
        compassSessions[appId] = session
        return session
    }

    private static func syncCompass(appId: String) {
        let listening = existingSource(appId: appId, channel: .compass)?.listening ?? false
        guard listening else {
            lock.lock()
            let session = compassSessions.removeValue(forKey: appId)
            lock.unlock()
            session?.invalidate()
            failPendingReceipts(session: session)
            return
        }

        let session = acquireCompassSession(appId: appId)
        switch session.manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            session.startHeadingIfNeeded()
        case .notDetermined:
            // 授权未决：发起系统请求，startCompass 回执挂到授权回调补点
            session.requestAuthorizationIfNeeded()
        default: // .denied / .restricted
            // onCompassChange wire 上没有 fail 回调，只能记日志；startCompass 的
            // fail 回执由 start 入口直接点（见 startCompass handler）
            DMPLogger.debug("SensorAPI \(appId) compass: location authorization denied, cannot listen")
        }
    }

    private static func startHeadingIfCompassListening(appId: String) {
        guard existingSource(appId: appId, channel: .compass)?.listening == true else { return }
        acquireCompassSession(appId: appId).startHeadingIfNeeded()
    }

    private static func flushPendingStartReceipts(appId: String, granted: Bool) {
        lock.lock()
        let receipts = compassSessions[appId]?.pendingStartReceipts ?? []
        compassSessions[appId]?.pendingStartReceipts.removeAll()
        lock.unlock()
        receipts.forEach { callback in
            if granted {
                DMPContainerApi.invokeSuccess(callback: callback, param: DMPMap(["errMsg": "startCompass:ok"]))
            } else {
                DMPContainerApi.invokeFailure(callback: callback, param: nil,
                                              errMsg: "startCompass:fail location authorization denied")
            }
        }
    }

    private static func failPendingReceipts(session: CompassSession?) {
        guard let session, !session.pendingStartReceipts.isEmpty else { return }
        let receipts = session.pendingStartReceipts
        session.pendingStartReceipts.removeAll()
        receipts.forEach { callback in
            // 授权还没落定会话就被拆除（stop/clearApp），与真被拒的文案区分开
            DMPContainerApi.invokeFailure(callback: callback, param: nil,
                                          errMsg: "startCompass:fail stopped before authorization resolved")
        }
    }

    // MARK: - 数据回点火

    /// 按 keep id 回点火：照 DMPContainer.makeBridgeCallback 的分发形状点
    /// type=triggerCallback（body.id=常驻 id、body.args=payload），fire 前按 interval
    /// 节流兜底（对齐 Android lastFireAt）。app 实例已释放时回收该 app 全部采样源。
    private static func fire(appId: String, channel: Channel, payload: [String: Any]) {
        let nowMs = DispatchTime.now().uptimeNanoseconds / 1_000_000
        var callbacks: [DMPBridgeCallback] = []
        var appIsGone = false

        lock.lock()
        if let source = sources[sourceKey(appId: appId, channel: channel)] {
            if source.hasAppRef, source.app == nil {
                appIsGone = true
            } else if source.listening {
                if nowMs >= source.lastFireAtMs + UInt64(source.intervalMs) {
                    source.lastFireAtMs = nowMs
                    callbacks = Array(source.callbacks.values)
                }
            }
        }
        lock.unlock()

        if appIsGone {
            clearApp(appId)
            return
        }
        guard !callbacks.isEmpty else { return }
        let result = DMPMap(payload)
        callbacks.forEach { $0(result, .success) }
    }

    // MARK: - Bridge 注册（四通道 16 个 wire API，与 JS/Android 名字完全一致）

    @BridgeMethod(Channel.accelerometer.startApi)
    var startAccelerometer: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleStart(appId: env.appId, app: DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
                              channel: .accelerometer, param: param, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.accelerometer.stopApi)
    var stopAccelerometer: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleStop(appId: env.appId, app: nil, channel: .accelerometer, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.accelerometer.onApi)
    var onAccelerometerChange: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleOn(appId: env.appId, app: DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
                           channel: .accelerometer, param: param, callback: callback)
        return DMPNoneResult()
    }

    @BridgeMethod(Channel.accelerometer.offApi)
    var offAccelerometerChange: DMPBridgeMethodHandler = { param, env, _ in
        SensorAPI.handleOff(appId: env.appId, channel: .accelerometer, param: param)
        return DMPNoneResult()
    }

    @BridgeMethod(Channel.compass.startApi)
    var startCompass: DMPBridgeMethodHandler = { param, env, callback in
        let appId = env.appId
        let app = DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex)
        // CLLocationManager 的授权查询/请求须在带 RunLoop 的线程上做
        DispatchQueue.main.async {
            switch CLLocationManager.authorizationStatus() {
            case .authorizedAlways, .authorizedWhenInUse:
                let source = SensorAPI.sourceLocked(appId: appId, app: app, channel: .compass)
                source.listening = true
                SensorAPI.syncSource(source)
                DMPContainerApi.invokeSuccess(callback: callback, param: DMPMap(["errMsg": "startCompass:ok"]))
            case .notDetermined:
                // 授权未决：先按契约登记 listening（授权落定后 delegate 会按它拉起
                // heading），再发起系统请求；startCompass 回执挂到授权落定后补点
                let source = SensorAPI.sourceLocked(appId: appId, app: app, channel: .compass)
                source.listening = true
                let session = SensorAPI.acquireCompassSession(appId: appId)
                if let callback { session.pendingStartReceipts.append(callback) }
                session.requestAuthorizationIfNeeded()
            default:
                DMPContainerApi.invokeFailure(callback: callback, param: nil,
                                              errMsg: "startCompass:fail location authorization denied")
            }
        }
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.compass.stopApi)
    var stopCompass: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleStop(appId: env.appId, app: nil, channel: .compass, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.compass.onApi)
    var onCompassChange: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleOn(appId: env.appId, app: DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
                           channel: .compass, param: param, callback: callback)
        return DMPNoneResult()
    }

    @BridgeMethod(Channel.compass.offApi)
    var offCompassChange: DMPBridgeMethodHandler = { param, env, _ in
        SensorAPI.handleOff(appId: env.appId, channel: .compass, param: param)
        return DMPNoneResult()
    }

    @BridgeMethod(Channel.gyroscope.startApi)
    var startGyroscope: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleStart(appId: env.appId, app: DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
                              channel: .gyroscope, param: param, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.gyroscope.stopApi)
    var stopGyroscope: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleStop(appId: env.appId, app: nil, channel: .gyroscope, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.gyroscope.onApi)
    var onGyroscopeChange: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleOn(appId: env.appId, app: DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
                           channel: .gyroscope, param: param, callback: callback)
        return DMPNoneResult()
    }

    @BridgeMethod(Channel.gyroscope.offApi)
    var offGyroscopeChange: DMPBridgeMethodHandler = { param, env, _ in
        SensorAPI.handleOff(appId: env.appId, channel: .gyroscope, param: param)
        return DMPNoneResult()
    }

    @BridgeMethod(Channel.deviceMotion.startApi)
    var startDeviceMotionListening: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleStart(appId: env.appId, app: DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
                              channel: .deviceMotion, param: param, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.deviceMotion.stopApi)
    var stopDeviceMotionListening: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleStop(appId: env.appId, app: nil, channel: .deviceMotion, callback: callback)
        return DMPAsyncResult()
    }

    @BridgeMethod(Channel.deviceMotion.onApi)
    var onDeviceMotionChange: DMPBridgeMethodHandler = { param, env, callback in
        SensorAPI.handleOn(appId: env.appId, app: DMPAppManager.sharedInstance().getApp(appIndex: env.appIndex),
                           channel: .deviceMotion, param: param, callback: callback)
        return DMPNoneResult()
    }

    @BridgeMethod(Channel.deviceMotion.offApi)
    var offDeviceMotionChange: DMPBridgeMethodHandler = { param, env, _ in
        SensorAPI.handleOff(appId: env.appId, channel: .deviceMotion, param: param)
        return DMPNoneResult()
    }
}
