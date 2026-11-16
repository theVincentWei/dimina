//
//  AudioAPI.swift
//  dimina
//
//  Created by cap-night on 2026/11/17.
//

import AVFoundation
import Foundation

/**
 * Media - InnerAudioContext / RecorderManager 原生实现（契约 v1，iOS 侧）。
 *
 * wire 契约以 JS service 层为准（fe/packages/service/src/api/core/media/audio/index.js）：
 * - InnerAudio.instanceId 由 JS 生成（`inner_audio_<uuid>`），原生只接收参数，不回发。
 * - 控制调用（play/pause/...）带一次性成对 success/fail id；事件订阅（InnerAudio.on）
 *   的 `success` 字段挂的是常驻事件 listener id——所以 on/off 系列一律免 ack
 *   （回 ack 会被 JS callback 表当成事件触发，Android 真机教训）。
 * - `InnerAudio.getState` 无 success/fail，走 DMPSyncResult 同步返回
 *   {duration, currentTime, paused}。
 * - 行为细节参照安卓 AudioApi.kt / RecorderApi.kt：errCode 映射（10001 系统 /
 *   10002 网络 / 10003 文件 / 10004 格式不支持 / 10005 解码）、事件归一
 *   （on 前缀大小写不敏感）、timeUpdate 200ms、录音仅 aac/wav、时长默认 60s 上限 600s。
 *
 * 线程模型：会话表静态 + NSLock（学 NetworkTypeAPI）；每个实例的 AVPlayer 操作收敛到
 * 自建串行队列（学 VideoDecoderSession）；事件推送在锁外派发 triggerCallback。
 */

// MARK: - 播放规则（对齐安卓 InnerAudioRules）

private enum InnerAudioRules {
    static let ERR_SYSTEM = 10001
    static let ERR_NETWORK = 10002
    static let ERR_FILE = 10003
    static let ERR_FORMAT = 10004
    static let ERR_DECODE = 10005

    /// 微信 11 个播放事件；on 前缀大小写不敏感容错，归一成 camelCase 规范名。
    static let canonicalEvents: Set<String> = [
        "canplay", "play", "pause", "stop", "ended", "timeUpdate", "error",
        "waiting", "seeking", "seeked", "loadedmetadata",
    ]

    static func normalizeEvent(_ event: String) -> String? {
        let trimmed = event.trimmingCharacters(in: .whitespacesAndNewlines)
        var stripped = trimmed
        if stripped.count > 2, stripped.lowercased().hasPrefix("on") {
            stripped = String(stripped.dropFirst(2))
        }
        guard !stripped.isEmpty else { return nil }
        let canonical = String(stripped.prefix(1)).lowercased() + String(stripped.dropFirst())
        return canonicalEvents.contains(canonical) ? canonical : nil
    }

    /// AVPlayerItem.error → 微信 errCode。
    static func mapItemError(_ error: Error?) -> Int {
        guard let error else { return ERR_SYSTEM }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain { return ERR_NETWORK }
        if nsError.domain == AVFoundationErrorDomain {
            switch nsError.code {
            case AVError.Code.formatUnsupported.rawValue,
                 AVError.Code.fileFormatNotRecognized.rawValue,
                 AVError.Code.fileFailedToParse.rawValue,
                 AVError.Code.maximumNumberOfSamplesForFileFormatReached.rawValue:
                return ERR_FORMAT
            case AVError.Code.decoderNotFound.rawValue,
                 AVError.Code.decoderTemporarilyUnavailable.rawValue,
                 AVError.Code.decodeFailed.rawValue:
                return ERR_DECODE
            default:
                return ERR_SYSTEM
            }
        }
        switch nsError.code {
        case NSFileNoSuchFileError, NSFileReadNoSuchFileError, NSFileReadNoPermissionError:
            return ERR_FILE
        default:
            return ERR_SYSTEM
        }
    }

    /// volume 允许 0~1，越界收敛到边界；非法值回落默认 1。
    static func clampVolume(_ volume: Double) -> Double {
        guard volume.isFinite else { return 1.0 }
        return min(max(volume, 0.0), 1.0)
    }

    /// playbackRate 范围 0.5~2.0（微信语义），越界收敛；非法值回落默认 1.0。
    static func clampPlaybackRate(_ rate: Double) -> Double {
        guard rate.isFinite else { return 1.0 }
        return min(max(rate, 0.5), 2.0)
    }

    static func saneSeconds(_ time: CMTime) -> Double {
        guard time.isNumeric, time.seconds.isFinite, time.seconds > 0 else { return 0 }
        return time.seconds
    }
}

// MARK: - 录音规则（对齐安卓 RecorderRules）

private enum RecorderRules {
    static let ERR_SYSTEM = 10001
    static let ERR_PERMISSION = 10001
    static let ERR_FORMAT = 10004

    static let validSampleRates: Set<Int> = [8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000]
    static let minEncodeBitRate = 16000
    static let maxEncodeBitRate = 320000
    static let defaultDurationMs = 60000
    static let maxDurationMs = 600000

    /// 契约 v1：仅 aac | wav。JS 侧已先行过滤，这里兜底校验。
    static func validateFormat(_ format: String) -> Bool {
        return format == "aac" || format == "wav"
    }

    /// 全部合法返回 nil，否则返回 errMsg detail（`Recorder.start:fail <detail>`）。
    static func validateParams(sampleRate: Int, channels: Int, encodeBitRate: Int) -> String? {
        if !validSampleRates.contains(sampleRate) { return "invalid sampleRate" }
        if channels < 1 || channels > 2 { return "invalid numberOfChannels" }
        if encodeBitRate < minEncodeBitRate || encodeBitRate > maxEncodeBitRate { return "invalid encodeBitRate" }
        return nil
    }

    /// duration ≤ 0 用默认 60000，超过上限 600000 收敛到上限（微信语义）。
    static func clampDuration(_ ms: Int) -> Int {
        if ms <= 0 { return defaultDurationMs }
        return min(ms, maxDurationMs)
    }

    /// wire 名（Recorder.onXxx / Recorder.offXxx 的 Xxx 部分）→ 规范事件名；未知返回 nil。
    static func eventForWire(_ op: String) -> String? {
        switch op {
        case "onStart", "offStart": return "start"
        case "onPause", "offPause": return "pause"
        case "onStop", "offStop": return "stop"
        case "onError", "offError": return "error"
        case "onInterruptionBegin", "offInterruptionBegin": return "interruptionBegin"
        case "onInterruptionEnd", "offInterruptionEnd": return "interruptionEnd"
        default: return nil
        }
    }
}

// MARK: - 录音活跃时长累计（pause 不计时，对齐安卓 ElapsedTimer）

private final class ElapsedTimer {
    private var anchor: Int64 = -1
    private var accumulated: Int64 = 0

    private static func nowMs() -> Int64 {
        return Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
    }

    func start() {
        anchor = ElapsedTimer.nowMs()
    }

    func pause() {
        if anchor >= 0 {
            accumulated += ElapsedTimer.nowMs() - anchor
            anchor = -1
        }
    }

    func resume() {
        if anchor < 0 { anchor = ElapsedTimer.nowMs() }
    }

    func stop() -> Int64 {
        pause()
        return accumulated
    }
}

private struct AudioError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

// MARK: - 数值/布尔参数提取

private enum AudioParam {
    static func bool(_ any: Any?) -> Bool? {
        if let value = any as? Bool { return value }
        if let number = any as? NSNumber { return number.boolValue }
        return nil
    }

    static func double(_ any: Any?) -> Double? {
        if let number = any as? NSNumber { return number.doubleValue }
        if let value = any as? Double { return value }
        if let value = any as? Int { return Double(value) }
        if let value = any as? String { return Double(value) }
        return nil
    }

    static func int(_ any: Any?) -> Int? {
        if let number = any as? NSNumber { return number.intValue }
        if let value = any as? Int { return value }
        if let value = any as? String { return Int(value) }
        return nil
    }
}

// MARK: - API 入口

public final class AudioAPI: DMPContainerApi {

    // MARK: InnerAudioContext（wire 名与 JS 完全一致）

    @BridgeMethod("InnerAudio.setSrc")
    var innerSetSrc: DMPBridgeMethodHandler = { param, env, callback in
        let map = param.getMap()
        guard let instanceId = map.getString(key: "instanceId"), !instanceId.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.setSrc:fail missing instanceId")
            return DMPAsyncResult()
        }
        guard let src = map.getString(key: "src"), !src.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.setSrc:fail invalid src")
            return DMPAsyncResult()
        }
        InnerAudioStore.sweepDeadApps()
        guard let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: true) else {
            AudioAPI.fail(callback, "InnerAudio.setSrc:fail create session failed")
            return DMPAsyncResult()
        }
        session.setSrc(src)
        // 与安卓一致：setSrc 立即回 ok，加载失败另路推 error 事件。
        AudioAPI.ok(callback, "InnerAudio.setSrc:ok")
        return DMPAsyncResult()
    }

    @BridgeMethod("InnerAudio.play")
    var innerPlay: DMPBridgeMethodHandler = { param, env, callback in
        guard let instanceId = param.getMap().getString(key: "instanceId"), !instanceId.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.play:fail missing instanceId")
            return DMPAsyncResult()
        }
        InnerAudioStore.sweepDeadApps()
        guard let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: false) else {
            AudioAPI.fail(callback, "InnerAudio.play:fail instance not found")
            return DMPAsyncResult()
        }
        session.play()
        AudioAPI.ok(callback, "InnerAudio.play:ok")
        return DMPAsyncResult()
    }

    @BridgeMethod("InnerAudio.pause")
    var innerPause: DMPBridgeMethodHandler = { param, env, callback in
        guard let instanceId = param.getMap().getString(key: "instanceId"), !instanceId.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.pause:fail missing instanceId")
            return DMPAsyncResult()
        }
        guard let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: false) else {
            AudioAPI.fail(callback, "InnerAudio.pause:fail instance not found")
            return DMPAsyncResult()
        }
        session.pause()
        AudioAPI.ok(callback, "InnerAudio.pause:ok")
        return DMPAsyncResult()
    }

    @BridgeMethod("InnerAudio.stop")
    var innerStop: DMPBridgeMethodHandler = { param, env, callback in
        guard let instanceId = param.getMap().getString(key: "instanceId"), !instanceId.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.stop:fail missing instanceId")
            return DMPAsyncResult()
        }
        guard let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: false) else {
            AudioAPI.fail(callback, "InnerAudio.stop:fail instance not found")
            return DMPAsyncResult()
        }
        session.stop()
        AudioAPI.ok(callback, "InnerAudio.stop:ok")
        return DMPAsyncResult()
    }

    @BridgeMethod("InnerAudio.seek")
    var innerSeek: DMPBridgeMethodHandler = { param, env, callback in
        let map = param.getMap()
        guard let instanceId = map.getString(key: "instanceId"), !instanceId.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.seek:fail missing instanceId")
            return DMPAsyncResult()
        }
        InnerAudioStore.sweepDeadApps()
        guard let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: false) else {
            AudioAPI.fail(callback, "InnerAudio.seek:fail instance not found")
            return DMPAsyncResult()
        }
        guard let position = AudioParam.double(map.get("position")), position.isFinite, position >= 0 else {
            AudioAPI.fail(callback, "InnerAudio.seek:fail invalid position")
            return DMPAsyncResult()
        }
        session.seek(position: position)
        AudioAPI.ok(callback, "InnerAudio.seek:ok")
        return DMPAsyncResult()
    }

    @BridgeMethod("InnerAudio.setProperty")
    var innerSetProperty: DMPBridgeMethodHandler = { param, env, callback in
        let map = param.getMap()
        guard let instanceId = map.getString(key: "instanceId"), !instanceId.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.setProperty:fail missing instanceId")
            return DMPAsyncResult()
        }
        guard let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: false) else {
            AudioAPI.fail(callback, "InnerAudio.setProperty:fail instance not found")
            return DMPAsyncResult()
        }
        session.applyProperties(map)
        AudioAPI.ok(callback, "InnerAudio.setProperty:ok")
        return DMPAsyncResult()
    }

    /// 同步返回播放状态；JS 侧 `syncState()` 直接消费返回值（无 success/fail id）。
    @BridgeMethod("InnerAudio.getState")
    var innerGetState: DMPBridgeMethodHandler = { param, env, _ in
        let instanceId = param.getMap().getString(key: "instanceId") ?? ""
        if !instanceId.isEmpty,
           let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: false) {
            return DMPSyncResult(session.snapshotState().toDictionary())
        }
        // 未注册实例：与安卓一致返回默认快照，不报错。
        return DMPSyncResult(DMPMap(["duration": 0.0, "currentTime": 0.0, "paused": true]).toDictionary())
    }

    /// 事件订阅。免 ack：success 挂的就是事件 listener id，回 ack 会被 JS 当事件假触发。
    @BridgeMethod("InnerAudio.on")
    var innerOn: DMPBridgeMethodHandler = { param, env, callback in
        let map = param.getMap()
        guard let instanceId = map.getString(key: "instanceId"), !instanceId.isEmpty else {
            DMPLogger.debug("InnerAudio.on: missing instanceId")
            return DMPNoneResult()
        }
        let rawEvent = map.getString(key: "event") ?? ""
        guard let event = InnerAudioRules.normalizeEvent(rawEvent) else {
            DMPLogger.debug("InnerAudio.on: unsupported event \(rawEvent)")
            return DMPNoneResult()
        }
        guard let callbackId = map.getString(key: "success"), !callbackId.isEmpty, let callback else {
            DMPLogger.debug("InnerAudio.on: invalid callback")
            return DMPNoneResult()
        }
        InnerAudioStore.sweepDeadApps()
        // 惰性建会话：微信典型用法是先 onCanplay 再 setSrc，订阅不能因为 setSrc 未到而丢失。
        guard let session = InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: true) else {
            DMPLogger.debug("InnerAudio.on: create session failed")
            return DMPNoneResult()
        }
        session.registerListener(event: event, id: callbackId, callback: callback)
        return DMPNoneResult()
    }

    /// 事件注销。免 ack（同 InnerAudio.on）。
    @BridgeMethod("InnerAudio.off")
    var innerOff: DMPBridgeMethodHandler = { param, env, _ in
        let map = param.getMap()
        guard let instanceId = map.getString(key: "instanceId"), !instanceId.isEmpty else {
            return DMPNoneResult()
        }
        let rawEvent = map.getString(key: "event") ?? ""
        guard let event = InnerAudioRules.normalizeEvent(rawEvent) else {
            DMPLogger.debug("InnerAudio.off: unsupported event \(rawEvent)")
            return DMPNoneResult()
        }
        let callbackId = map.getString(key: "success")
        InnerAudioStore.session(appId: env.appId, instanceId: instanceId, create: false)?
            .removeListener(event: event, id: callbackId)
        return DMPNoneResult()
    }

    @BridgeMethod("InnerAudio.destroy")
    var innerDestroy: DMPBridgeMethodHandler = { param, env, callback in
        guard let instanceId = param.getMap().getString(key: "instanceId"), !instanceId.isEmpty else {
            AudioAPI.fail(callback, "InnerAudio.destroy:fail missing instanceId")
            return DMPAsyncResult()
        }
        if let session = InnerAudioStore.remove(appId: env.appId, instanceId: instanceId) {
            session.destroy()
        }
        // 与安卓一致：实例不存在时销毁同样回 ok。
        AudioAPI.ok(callback, "InnerAudio.destroy:ok")
        return DMPAsyncResult()
    }

    // MARK: RecorderManager（wire 名与 JS 完全一致）

    @BridgeMethod("Recorder.start")
    var recorderStart: DMPBridgeMethodHandler = { param, env, callback in
        let map = param.getMap()
        let format = map.getString(key: "format") ?? "aac"
        let sampleRate = AudioParam.int(map.get("sampleRate")) ?? 8000
        let channels = AudioParam.int(map.get("numberOfChannels")) ?? 1
        let encodeBitRate = AudioParam.int(map.get("encodeBitRate")) ?? 48000
        let durationMs = RecorderRules.clampDuration(AudioParam.int(map.get("duration")) ?? RecorderRules.defaultDurationMs)

        // JS 侧已过滤非法格式，这里兜底 fail（契约钉死 errCode 10004）。
        guard RecorderRules.validateFormat(format) else {
            AudioAPI.failWithCode(callback, "Recorder.start:fail invalid format", code: RecorderRules.ERR_FORMAT)
            return DMPAsyncResult()
        }
        if let detail = RecorderRules.validateParams(sampleRate: sampleRate, channels: channels, encodeBitRate: encodeBitRate) {
            AudioAPI.fail(callback, "Recorder.start:fail \(detail)")
            return DMPAsyncResult()
        }
        RecorderStore.sweepDeadApps()
        AudioAPI.ensureInterruptionObserver()
        guard let session = RecorderStore.session(appId: env.appId, create: true) else {
            AudioAPI.fail(callback, "Recorder.start:fail create session failed")
            return DMPAsyncResult()
        }
        return session.beginStart(
            format: format, sampleRate: sampleRate, channels: channels,
            encodeBitRate: encodeBitRate, durationMs: durationMs, callback: callback
        )
    }

    @BridgeMethod("Recorder.pause")
    var recorderPause: DMPBridgeMethodHandler = { param, env, callback in
        RecorderStore.sweepDeadApps()
        guard let session = RecorderStore.session(appId: env.appId, create: false) else {
            AudioAPI.fail(callback, "Recorder.pause:fail no recording")
            return DMPAsyncResult()
        }
        return session.beginPause(callback: callback)
    }

    @BridgeMethod("Recorder.resume")
    var recorderResume: DMPBridgeMethodHandler = { param, env, callback in
        RecorderStore.sweepDeadApps()
        guard let session = RecorderStore.session(appId: env.appId, create: false) else {
            AudioAPI.fail(callback, "Recorder.resume:fail no paused recording")
            return DMPAsyncResult()
        }
        return session.beginResume(callback: callback)
    }

    @BridgeMethod("Recorder.stop")
    var recorderStop: DMPBridgeMethodHandler = { param, env, callback in
        RecorderStore.sweepDeadApps()
        guard let session = RecorderStore.session(appId: env.appId, create: false) else {
            AudioAPI.fail(callback, "Recorder.stop:fail no recording")
            return DMPAsyncResult()
        }
        return session.beginStop(callback: callback)
    }

    // MARK: Recorder 事件订阅（on/off 共用；一律免 ack，理由同 InnerAudio.on）

    @BridgeMethod("Recorder.onStart")
    var recorderOnStart: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("onStart", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.offStart")
    var recorderOffStart: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("offStart", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.onPause")
    var recorderOnPause: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("onPause", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.offPause")
    var recorderOffPause: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("offPause", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.onStop")
    var recorderOnStop: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("onStop", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.offStop")
    var recorderOffStop: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("offStop", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.onError")
    var recorderOnError: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("onError", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.offError")
    var recorderOffError: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("offError", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.onInterruptionBegin")
    var recorderOnInterruptionBegin: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("onInterruptionBegin", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.offInterruptionBegin")
    var recorderOffInterruptionBegin: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("offInterruptionBegin", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.onInterruptionEnd")
    var recorderOnInterruptionEnd: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("onInterruptionEnd", param, env, callback)
        return DMPNoneResult()
    }

    @BridgeMethod("Recorder.offInterruptionEnd")
    var recorderOffInterruptionEnd: DMPBridgeMethodHandler = { param, env, callback in
        AudioAPI.recorderListen("offInterruptionEnd", param, env, callback)
        return DMPNoneResult()
    }

    // MARK: - 生命周期清理入口

    /// 小程序会话结束时释放该 appId 的全部音频资源（播放器、录音引擎、订阅表）。
    /// DMPApp.destroy() 不在本任务可改文件内，暂未接线；调用方接入后即可生效，
    /// 现阶段由 sweepDeadApps（每次 wire 调用入口）兜底回收已死 app 的会话。
    public static func clearApp(appId: String) {
        InnerAudioStore.clearApp(appId)
        RecorderStore.clearApp(appId)
    }

    // MARK: - 私有工具

    fileprivate static func ok(_ callback: DMPBridgeCallback?, _ message: String) {
        DMPContainerApi.invokeSuccess(callback: callback, param: DMPMap(["errMsg": message]))
    }

    fileprivate static func fail(_ callback: DMPBridgeCallback?, _ message: String) {
        DMPContainerApi.invokeFailure(callback: callback, param: nil, errMsg: message)
    }

    /// fail 回执带业务 errCode（如格式 10004 / 权限 10001）。
    fileprivate static func failWithCode(_ callback: DMPBridgeCallback?, _ message: String, code: Int) {
        DMPContainerApi.invokeCallback(callback, type: .fail, param: DMPMap(["errMsg": message, "errCode": code]))
    }

    private static func recorderListen(_ op: String, _ param: DMPBridgeParam, _ env: DMPBridgeEnv, _ callback: DMPBridgeCallback?) {
        guard let event = RecorderRules.eventForWire(op) else {
            DMPLogger.debug("Recorder listen: unsupported wire \(op)")
            return
        }
        RecorderStore.sweepDeadApps()
        guard let session = RecorderStore.session(appId: env.appId, create: true) else {
            DMPLogger.debug("Recorder listen: create session failed")
            return
        }
        let callbackId = param.getMap().getString(key: "success")
        if op.hasPrefix("on") {
            guard let callbackId, !callbackId.isEmpty, let callback else {
                DMPLogger.debug("Recorder.\(op): invalid callback")
                return
            }
            session.registerListener(event: event, id: callbackId, callback: callback)
        } else {
            session.removeListener(event: event, id: callbackId)
        }
    }

    // MARK: - 电话/系统打断监听（全局一份，派发给各录音会话）

    private static let interruptionLock = NSLock()
    private static var interruptionObserver: NSObjectProtocol?

    private static func ensureInterruptionObserver() {
        interruptionLock.lock()
        if interruptionObserver == nil {
            interruptionObserver = NotificationCenter.default.addObserver(
                forName: AVAudioSession.interruptionNotification,
                object: AVAudioSession.sharedInstance(),
                queue: nil
            ) { note in
                AudioAPI.dispatchInterruption(note)
            }
        }
        interruptionLock.unlock()
    }

    private static func dispatchInterruption(_ note: Notification) {
        let info = note.userInfo ?? [:]
        let rawType = info[AVAudioSessionInterruptionTypeKey] as? UInt ?? 0
        let type = AVAudioSession.InterruptionType(rawValue: rawType) ?? .began
        let rawOptions = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
        let options = AVAudioSession.InterruptionOptions(rawValue: rawOptions)
        let shouldResume = options.contains(.shouldResume)
        RecorderStore.allSessions().forEach { session in
            session.handleInterruption(type: type, shouldResume: shouldResume)
        }
    }
}

// MARK: - 播放会话存储

private enum InnerAudioStore {
    static let lock = NSLock()
    static var sessions: [String: [String: InnerAudioSession]] = [:]

    static func session(appId: String, instanceId: String, create: Bool) -> InnerAudioSession? {
        lock.lock()
        defer { lock.unlock() }
        if let existing = sessions[appId]?[instanceId] { return existing }
        guard create else { return nil }
        let session = InnerAudioSession(appId: appId, instanceId: instanceId)
        sessions[appId, default: [:]][instanceId] = session
        return session
    }

    static func remove(appId: String, instanceId: String) -> InnerAudioSession? {
        lock.lock()
        defer { lock.unlock() }
        let removed = sessions[appId]?.removeValue(forKey: instanceId)
        if sessions[appId]?.isEmpty == true {
            sessions.removeValue(forKey: appId)
        }
        return removed
    }

    static func clearApp(_ appId: String) {
        var doomed: [InnerAudioSession] = []
        lock.lock()
        doomed = Array((sessions.removeValue(forKey: appId) ?? [:]).values)
        lock.unlock()
        doomed.forEach { $0.destroy() }
    }

    /// 回收已关闭小程序的会话（DMPApp.destroy 暂未接线，wire 调用入口兜底清理）。
    static func sweepDeadApps() {
        var doomed: [InnerAudioSession] = []
        lock.lock()
        for (appId, instances) in sessions where DMPAppManager.sharedInstance().existApp(appId: appId) == nil {
            doomed.append(contentsOf: instances.values)
            sessions.removeValue(forKey: appId)
        }
        lock.unlock()
        doomed.forEach { $0.destroy() }
    }

    /// 小程序 src → 可播放 URL：difile:// 虚拟路径 / http(s) 直连 / 包内相对路径。
    static func resolveSource(_ source: String, appId: String) throws -> URL {
        if let path = DMPFileUtil.sandboxPathFromVPath(from: source, appId: appId) {
            guard FileManager.default.fileExists(atPath: path) else {
                throw AudioError("file does not exist")
            }
            return URL(fileURLWithPath: path)
        }
        if let url = URL(string: source), let scheme = url.scheme, !scheme.isEmpty {
            let lower = scheme.lowercased()
            if lower == "http" || lower == "https" {
                guard url.host != nil else { throw AudioError("invalid url") }
                return url
            }
            if lower == "file" {
                return url
            }
            throw AudioError("unsupported scheme \(scheme)")
        }
        // 包内相对路径（兼容 ?query/#fragment 后缀），候选 [相对路径, main/相对路径]。
        let relativePath = source
            .split(separator: "?", maxSplits: 1).first.map(String.init)?
            .split(separator: "#", maxSplits: 1).first.map(String.init)?
            .trimmingCharacters(in: CharacterSet(charactersIn: "/")) ?? ""
        let root = URL(fileURLWithPath: DMPSandboxManager.appBundlePath(appId), isDirectory: true).resolvingSymlinksInPath()
        for candidate in [relativePath, "main/\(relativePath)"] {
            let url = root.appendingPathComponent(candidate).resolvingSymlinksInPath()
            if url.path.hasPrefix(root.path + "/"), FileManager.default.fileExists(atPath: url.path) {
                return url
            }
        }
        throw AudioError("file does not exist")
    }
}

// MARK: - 单个 InnerAudioContext 实例

/**
 * 一个 AVPlayer + 一张事件表 + 一个 200ms 节流 ticker。
 * 状态字段统一由 session 锁保护（getState 从 JS 线程同步读）；AVPlayer/AVPlayerItem
 * 及其 KVO/时间观察全部收敛在自建串行队列上，代际号防跨 setSrc 的陈旧回调。
 */
private final class InnerAudioSession {
    let appId: String
    let instanceId: String
    let queue = DispatchQueue(label: "com.didi.dimina.inneraudio", qos: .userInitiated)
    private let lock = NSLock()

    // 配置（wire 线程写，队列读）
    private var src = ""
    private var volume = 1.0
    private var playbackRate = 1.0
    private var startTime = 0.0
    private var autoplay = false
    private var loop = false
    private var obeyMuteSwitch = true
    private var lastOp = "setSrc"

    // 实时状态（队列写，JS 线程经 getState 同步读）
    private var paused = true
    private var cachedDuration: Double = 0
    private var cachedCurrentTime: Double = 0
    private var prepared = false
    private var stopped = false
    private var errored = false
    private var ended = false
    private var pendingPlay = false
    private var pendingSeek: Double?
    private var loadGeneration: UInt64 = 0
    private var destroyed = false
    private var listeners: [String: [String: DMPBridgeCallback]] = [:]

    // 队列独占（不加锁）
    private var player: AVPlayer?
    private var item: AVPlayerItem?
    private var statusObservation: NSKeyValueObservation?
    private var endedObserver: NSObjectProtocol?
    private var timeObserver: Any?

    init(appId: String, instanceId: String) {
        self.appId = appId
        self.instanceId = instanceId
    }

    // MARK: 会话锁工具

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    // MARK: 事件表

    func registerListener(event: String, id: String, callback: @escaping DMPBridgeCallback) {
        withLock {
            guard !destroyed else { return }
            listeners[event, default: [:]][id] = callback
        }
    }

    func removeListener(event: String, id: String?) {
        withLock {
            if let id {
                listeners[event]?.removeValue(forKey: id)
            } else {
                listeners[event] = nil
            }
        }
    }

    func snapshotState() -> DMPMap {
        return withLock {
            DMPMap([
                "duration": cachedDuration,
                "currentTime": cachedCurrentTime,
                "paused": paused,
            ])
        }
    }

    // MARK: 事件派发（锁外回 JS）

    private func emit(_ event: String, _ payload: DMPMap) {
        let callbacks: [DMPBridgeCallback] = withLock {
            guard let table = listeners[event] else { return [] }
            return Array(table.values)
        }
        guard !callbacks.isEmpty else { return }
        guard DMPAppManager.sharedInstance().existApp(appId: appId) != nil else { return }
        callbacks.forEach { $0(payload, .success) }
    }

    private func emitError(_ detail: String, _ errCode: Int) {
        let op = withLock { lastOp }
        emit("error", DMPMap([
            "errCode": errCode,
            "errMsg": "InnerAudio.\(op):fail \(detail)",
        ]))
    }

    // MARK: wire 操作

    func setSrc(_ nextSrc: String) {
        let generation: UInt64 = withLock {
            lastOp = "setSrc"
            src = nextSrc
            pendingPlay = false
            pendingSeek = nil
            paused = true
            stopped = false
            errored = false
            ended = false
            prepared = false
            cachedDuration = 0
            cachedCurrentTime = 0
            loadGeneration += 1
            return loadGeneration
        }
        queue.async { self.loadSource(generation: generation) }
    }

    /// 错误态重建播放链（play after error）。
    private func reload() {
        let generation: UInt64 = withLock {
            loadGeneration += 1
            prepared = false
            paused = true
            cachedDuration = 0
            cachedCurrentTime = 0
            return loadGeneration
        }
        queue.async { self.loadSource(generation: generation) }
    }

    func play() {
        queue.async { self.playNow() }
    }

    func pause() {
        queue.async { self.pauseNow() }
    }

    func stop() {
        queue.async { self.stopNow() }
    }

    func seek(position: Double) {
        queue.async { self.seekNow(position) }
    }

    func applyProperties(_ map: DMPMap) {
        // 参数在 wire 线程先取值，落盘后的应用放队列。
        let newLoop = AudioParam.bool(map.get("loop"))
        let newVolume = AudioParam.double(map.get("volume")).map(InnerAudioRules.clampVolume)
        let newRate = AudioParam.double(map.get("playbackRate")).map(InnerAudioRules.clampPlaybackRate)
        let newStartTime = AudioParam.double(map.get("startTime")).map {
            $0.isFinite && $0 > 0 ? $0 : 0
        }
        let newAutoplay = AudioParam.bool(map.get("autoplay"))
        let newObeyMuteSwitch = AudioParam.bool(map.get("obeyMuteSwitch"))
        var volumeToApply: Double?
        var rateToApply: Double?
        withLock {
            lastOp = "setProperty"
            if let value = newLoop { loop = value }
            if let value = newVolume { volume = value }
            if let value = newRate { playbackRate = value }
            if let value = newStartTime { startTime = value }
            if let value = newAutoplay { autoplay = value }
            if let value = newObeyMuteSwitch { obeyMuteSwitch = value }
            volumeToApply = volume
            rateToApply = playbackRate
        }
        queue.async {
            guard let player = self.player else { return }
            if let value = volumeToApply { player.volume = Float(value) }
            // rate 仅在播放中即时生效；起播路径会重新套用。
            if let value = rateToApply, player.rate != 0 { player.rate = Float(value) }
        }
    }

    func destroy() {
        let wasDestroyed: Bool = withLock {
            if destroyed { return true }
            destroyed = true
            return false
        }
        guard !wasDestroyed else { return }
        queue.async { self.teardownPlayer() }
    }

    // MARK: 队列内实现

    private func loadSource(generation: UInt64) {
        let source = withLock { src }
        guard !source.isEmpty else { return }
        do {
            let url = try InnerAudioStore.resolveSource(source, appId: appId)
            withLock {
                guard !destroyed, loadGeneration == generation else { return }
                installItem(url: url)
            }
        } catch {
            let deliver: Bool = withLock {
                guard !destroyed, loadGeneration == generation else { return false }
                paused = true
                prepared = false
                errored = true
                return true
            }
            if deliver {
                emitError("resolve src failed: \(error.localizedDescription)", InnerAudioRules.ERR_FILE)
            }
        }
    }

    /// 换装 AVPlayerItem（仅队列内调用）。
    private func installItem(url: URL) {
        teardownItem()
        let player = ensurePlayer()
        let item = AVPlayerItem(url: url)
        self.item = item
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] observed, _ in
            guard let self else { return }
            let capturedItem = observed
            self.queue.async { self.handleItemStatus(capturedItem) }
        }
        // .AVPlayerItemDidPlayToEndTime（iOS 14 可用；didPlayToEndTime 嵌套名是 iOS 16+）。
        endedObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            let capturedItem = item
            self.queue.async { self.handleEnded(capturedItem) }
        }
        // 代际校验在 handleItemStatus 内比对 item 身份，无需额外代际号。
        player.replaceCurrentItem(with: item)
    }

    private func ensurePlayer() -> AVPlayer {
        if let player { return player }
        let player = AVPlayer()
        // 200ms timeUpdate 节流：系统按时间推进派发，暂停期天然静默。
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 200, timescale: 1000),
            queue: queue
        ) { [weak self] _ in
            self?.handleTimeTick()
        }
        self.player = player
        return player
    }

    private func handleItemStatus(_ observed: AVPlayerItem) {
        enum Outcome {
            case ignore
            case ready(seekTo: Double?, autoStart: Bool)
            case failed(detail: String, code: Int)
        }
        let outcome: Outcome = withLock {
            guard !destroyed, item === observed else { return .ignore }
            switch observed.status {
            case .readyToPlay:
                prepared = true
                errored = false
                ended = false
                cachedDuration = InnerAudioRules.saneSeconds(observed.duration)
                cachedCurrentTime = 0
                // pendingSeek / startTime 自动落位不给 JS 发 seeked（微信语义）。
                let target = pendingSeek ?? (startTime > 0 ? startTime : nil)
                pendingSeek = nil
                let start = autoplay || pendingPlay
                pendingPlay = false
                return .ready(seekTo: target, autoStart: start)
            case .failed:
                prepared = false
                paused = true
                errored = true
                pendingPlay = false
                return .failed(
                    detail: observed.error?.localizedDescription ?? "player error",
                    code: InnerAudioRules.mapItemError(observed.error)
                )
            default:
                return .ignore
            }
        }
        switch outcome {
        case .ignore:
            break
        case .failed(let detail, let code):
            emitError(detail, code)
        case .ready(let seekTo, let autoStart):
            if let seekTo, seekTo > 0 {
                player?.seek(to: CMTime(seconds: seekTo, preferredTimescale: 600))
            }
            emit("canplay", DMPMap())
            emit("loadedmetadata", DMPMap())
            if autoStart {
                startPlayback()
            }
        }
    }

    private func startPlayback() {
        guard let player, let item, item.status == .readyToPlay else { return }
        // AVAudioSession .playback：混音出声，受任务要求固定该 category。
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        let rate = withLock { playbackRate }
        player.play()
        if abs(rate - 1.0) > 1e-6 {
            player.rate = Float(rate)
        }
        withLock {
            paused = false
            stopped = false
            errored = false
        }
        emit("play", DMPMap())
    }

    private func playNow() {
        withLock { lastOp = "play" }
        let source = withLock { src }
        guard !source.isEmpty else {
            emitError("no valid audio source", InnerAudioRules.ERR_FILE)
            return
        }
        guard let item, let player else {
            // setSrc 链路尚未落 item：记 pending-play，ready 时自动起播。
            withLock { pendingPlay = true }
            emit("waiting", DMPMap())
            return
        }
        switch item.status {
        case .readyToPlay:
            let wasEnded = withLock { ended }
            if wasEnded {
                player.seek(to: CMTime.zero)
            }
            withLock {
                ended = false
                stopped = false
                errored = false
            }
            startPlayback()
        case .failed:
            // 错误态必须重建播放链才能再播（对齐安卓 reprepare）。
            withLock {
                pendingPlay = true
                errored = false
                stopped = false
            }
            reload()
        default:
            withLock { pendingPlay = true }
            emit("waiting", DMPMap())
        }
    }

    private func pauseNow() {
        withLock {
            lastOp = "pause"
            pendingPlay = false
        }
        guard let player else { return }
        let canPause: Bool = withLock { prepared && !paused }
        guard canPause else { return }
        player.pause()
        withLock { paused = true }
        emit("pause", DMPMap())
    }

    private func stopNow() {
        withLock { lastOp = "stop" }
        let alreadyStopped = withLock { stopped }
        if alreadyStopped { return }
        withLock { pendingPlay = false }
        if let player {
            player.pause()
            player.seek(to: CMTime.zero)
        }
        withLock {
            stopped = true
            ended = false
            paused = true
            cachedCurrentTime = 0
        }
        emit("stop", DMPMap())
    }

    private func seekNow(_ position: Double) {
        withLock { lastOp = "seek" }
        guard let item else {
            withLock { pendingSeek = position }
            return
        }
        guard item.status == .readyToPlay, let player else {
            withLock { pendingSeek = position }
            return
        }
        withLock {
            ended = false
            cachedCurrentTime = position
        }
        emit("seeking", DMPMap())
        let generation = withLock { loadGeneration }
        player.seek(to: CMTime(seconds: position, preferredTimescale: 600)) { [weak self] _ in
            guard let self else { return }
            self.queue.async {
                let (current, gone) = self.withLock { (self.loadGeneration, self.destroyed) }
                guard !gone, current == generation else { return }
                self.emit("seeked", DMPMap())
            }
        }
    }

    private func handleTimeTick() {
        guard let player, let item, item.status == .readyToPlay else { return }
        guard player.rate != 0 else { return }
        let position = player.currentTime()
        guard position.isNumeric, position.seconds.isFinite else { return }
        let duration = InnerAudioRules.saneSeconds(item.duration)
        withLock {
            cachedCurrentTime = position.seconds
            if duration > 0 { cachedDuration = duration }
        }
        let payload = withLock {
            DMPMap(["currentTime": cachedCurrentTime, "duration": cachedDuration])
        }
        emit("timeUpdate", payload)
    }

    private func handleEnded(_ observed: AVPlayerItem) {
        let shouldLoop: Bool = withLock {
            guard !destroyed, item === observed else { return false }
            paused = true
            cachedCurrentTime = cachedDuration
            return loop
        }
        if shouldLoop {
            // 循环：自动重播，不发 ended（对齐安卓 isLooping 语义）。
            player?.seek(to: CMTime.zero)
            startPlayback()
            return
        }
        emit("ended", DMPMap())
    }

    /// 移除当前 item 的观察者（仅队列内调用；不移除时间观察与 player）。
    private func teardownItem() {
        statusObservation?.invalidate()
        statusObservation = nil
        if let endedObserver {
            NotificationCenter.default.removeObserver(endedObserver)
            self.endedObserver = nil
        }
        item = nil
    }

    private func teardownPlayer() {
        teardownItem()
        if let player, let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
        player?.replaceCurrentItem(with: nil)
        player = nil
        withLock { listeners.removeAll() }
    }
}

// MARK: - 录音会话存储

private enum RecorderStore {
    static let lock = NSLock()
    static var sessions: [String: RecorderSession] = [:]

    static func session(appId: String, create: Bool) -> RecorderSession? {
        lock.lock()
        defer { lock.unlock() }
        if let existing = sessions[appId] { return existing }
        guard create else { return nil }
        let session = RecorderSession(appId: appId)
        sessions[appId] = session
        return session
    }

    static func allSessions() -> [RecorderSession] {
        lock.lock()
        defer { lock.unlock() }
        return Array(sessions.values)
    }

    static func clearApp(_ appId: String) {
        let doomed: RecorderSession? = {
            lock.lock()
            defer { lock.unlock() }
            return sessions.removeValue(forKey: appId)
        }()
        doomed?.teardown()
    }

    /// 回收已关闭小程序的录音会话。
    static func sweepDeadApps() {
        var doomed: [RecorderSession] = []
        lock.lock()
        for (appId, session) in sessions where DMPAppManager.sharedInstance().existApp(appId: appId) == nil {
            doomed.append(session)
            sessions.removeValue(forKey: appId)
        }
        lock.unlock()
        doomed.forEach { $0.teardown() }
    }

    /// 是否还有任意小程序在录音（决定结束时是否 deactivate AVAudioSession）。
    static func hasActiveRecorder() -> Bool {
        return allSessions().contains { $0.isActive() }
    }
}

// MARK: - 单个 appId 的录音会话

/**
 * 录音状态机（IDLE / RECORDING / PAUSED）：所有可变状态收敛在会话串行队列，
 * wire 线程只做参数校验与状态表读取。aac = AVAudioRecorder(.m4a 容器)，
 * wav = AVAudioRecorder(LinearPCM .wav)；结束回调由 AVAudioRecorderDelegate 驱动，
 * 另设 2.5s 兜底 finalize 防 delegate 丢失导致 JS 回执悬挂。
 */
private final class RecorderSession {
    private enum State {
        case idle
        case recording
        case paused
    }

    private struct PendingStart {
        let format: String
        let sampleRate: Int
        let channels: Int
        let encodeBitRate: Int
        let durationMs: Int
        let callback: DMPBridgeCallback
    }

    let appId: String
    let queue = DispatchQueue(label: "com.didi.dimina.recorder", qos: .userInitiated)
    private let lock = NSLock()

    private var state: State = .idle
    private var listeners: [String: [String: DMPBridgeCallback]] = [:]
    private var pendingStart: PendingStart?
    private var pendingStop: DMPBridgeCallback?
    private var finalized = true
    private var pausedByInterruption = false
    private var elapsed = ElapsedTimer()

    // 队列独占
    private var engine: RecorderEngine?
    private var outputFile: URL?
    private var fallback: DispatchWorkItem?

    init(appId: String) {
        self.appId = appId
    }

    // MARK: 会话锁工具

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    func isActive() -> Bool {
        return withLock { state != .idle }
    }

    // MARK: 事件表

    func registerListener(event: String, id: String, callback: @escaping DMPBridgeCallback) {
        withLock {
            listeners[event, default: [:]][id] = callback
        }
    }

    func removeListener(event: String, id: String?) {
        withLock {
            if let id {
                listeners[event]?.removeValue(forKey: id)
            } else {
                listeners[event] = nil
            }
        }
    }

    private func emit(_ event: String, _ payload: DMPMap) {
        let callbacks: [DMPBridgeCallback] = withLock {
            guard let table = listeners[event] else { return [] }
            return Array(table.values)
        }
        guard !callbacks.isEmpty else { return }
        guard DMPAppManager.sharedInstance().existApp(appId: appId) != nil else { return }
        callbacks.forEach { $0(payload, .success) }
    }

    // MARK: start：校验已在 handler 完成，这里排队 + 麦克风权限 → 起引擎

    func beginStart(format: String, sampleRate: Int, channels: Int,
                    encodeBitRate: Int, durationMs: Int,
                    callback: DMPBridgeCallback?) -> DMPAPIResult {
        let busy: Bool = withLock {
            if pendingStart != nil || state != .idle { return true }
            pendingStart = PendingStart(
                format: format, sampleRate: sampleRate, channels: channels,
                encodeBitRate: encodeBitRate, durationMs: durationMs,
                callback: callback ?? { _, _ in }
            )
            return false
        }
        if busy {
            AudioAPI.fail(callback, "Recorder.start:fail busy")
            return DMPAsyncResult()
        }
        AudioAPI.requestMicPermission { [weak self] granted in
            guard let self else { return }
            self.queue.async { self.completePermission(granted: granted) }
        }
        return DMPAsyncResult()
    }

    private func completePermission(granted: Bool) {
        let pending: PendingStart? = withLock {
            let pending = pendingStart
            pendingStart = nil
            return pending
        }
        guard let pending else { return }
        guard granted else {
            // 契约钉死文案 + 权限档 errCode。
            AudioAPI.failWithCode(pending.callback, "Recorder.start:fail auth deny", code: RecorderRules.ERR_PERMISSION)
            return
        }
        startEngine(pending)
    }

    private func startEngine(_ pending: PendingStart) {
        let extensionName = pending.format == "aac" ? "m4a" : "wav"
        let directory = URL(
            fileURLWithPath: DMPSandboxManager.appTmpResourceDirectoryPath(appId: appId),
            isDirectory: true
        )
        var engine: RecorderEngine?
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let name = "REC_\(Int64(Date().timeIntervalSince1970 * 1000))_\(UUID().uuidString.prefix(8)).\(extensionName)"
            let file = directory.appendingPathComponent(name)
            try? AVAudioSession.sharedInstance().setCategory(.playAndRecord)
            try? AVAudioSession.sharedInstance().setActive(true)
            withLock { finalized = false }
            let created = RecorderEngine(
                queue: queue,
                onFinish: { [weak self] flag, _ in
                    self?.finalizeRecording(success: flag)
                },
                onEncodeError: { [weak self] detail in
                    self?.handleEngineError(detail)
                }
            )
            engine = created
            try created.start(
                to: file,
                format: pending.format,
                sampleRate: pending.sampleRate,
                channels: pending.channels,
                encodeBitRate: pending.encodeBitRate,
                maxDurationMs: pending.durationMs
            )
            self.engine = created
            outputFile = file
            withLock {
                state = .recording
                pausedByInterruption = false
                elapsed = ElapsedTimer()
                elapsed.start()
            }
            AudioAPI.ok(pending.callback, "Recorder.start:ok")
            emit("start", DMPMap())
        } catch {
            engine?.dispose()
            withLock {
                finalized = true
                state = .idle
                pausedByInterruption = false
            }
            outputFile = nil
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            let detail = error.localizedDescription
            AudioAPI.fail(pending.callback, "Recorder.start:fail \(detail)")
            emit("error", DMPMap([
                "errCode": RecorderRules.ERR_SYSTEM,
                "errMsg": "Recorder.start:fail \(detail)",
            ]))
        }
    }

    // MARK: pause / resume / stop

    func beginPause(callback: DMPBridgeCallback?) -> DMPAPIResult {
        let canPause: Bool = withLock { state == .recording && engine != nil }
        guard canPause else {
            AudioAPI.fail(callback, "Recorder.pause:fail no recording")
            return DMPAsyncResult()
        }
        queue.async {
            let stillRecording: Bool = self.withLock { self.state == .recording }
            guard stillRecording, let engine = self.engine else { return }
            engine.pauseRecording()
            self.withLock {
                self.elapsed.pause()
                self.state = .paused
            }
            self.emit("pause", DMPMap())
        }
        AudioAPI.ok(callback, "Recorder.pause:ok")
        return DMPAsyncResult()
    }

    func beginResume(callback: DMPBridgeCallback?) -> DMPAPIResult {
        let canResume: Bool = withLock { state == .paused && engine != nil }
        guard canResume else {
            AudioAPI.fail(callback, "Recorder.resume:fail no paused recording")
            return DMPAsyncResult()
        }
        queue.async {
            let stillPaused: Bool = self.withLock { self.state == .paused }
            guard stillPaused, let engine = self.engine else { return }
            engine.resumeRecording()
            self.withLock {
                self.elapsed.resume()
                self.state = .recording
            }
            // 微信没有 onResume 事件，仅恢复计时。
        }
        AudioAPI.ok(callback, "Recorder.resume:ok")
        return DMPAsyncResult()
    }

    func beginStop(callback: DMPBridgeCallback?) -> DMPAPIResult {
        var canStop = false
        var duplicated = false
        withLock {
            if state == .idle {
                canStop = false
            } else if pendingStop != nil {
                duplicated = true
            } else {
                canStop = true
                pendingStop = callback ?? { _, _ in }
            }
        }
        if !canStop {
            AudioAPI.fail(callback, duplicated
                ? "Recorder.stop:fail stop already in progress"
                : "Recorder.stop:fail no recording")
            return DMPAsyncResult()
        }
        queue.async { self.performStop() }
        return DMPAsyncResult()
    }

    /// duration 到限自动停与手动停共用：stop 引擎，等 delegate finalize。
    private func performStop() {
        guard let engine else {
            finalizeRecording(success: false)
            return
        }
        armFinalizeFallback()
        engine.stop()
    }

    // MARK: 打断（电话等）

    func handleInterruption(type: AVAudioSession.InterruptionType, shouldResume: Bool) {
        queue.async { self.applyInterruption(type: type, shouldResume: shouldResume) }
    }

    private func applyInterruption(type: AVAudioSession.InterruptionType, shouldResume: Bool) {
        if type == .began {
            let canPause: Bool = withLock { state == .recording && engine != nil }
            guard canPause else { return }
            engine?.pauseRecording()
            withLock {
                elapsed.pause()
                state = .paused
                pausedByInterruption = true
            }
            emit("interruptionBegin", DMPMap())
            return
        }
        let wasByInterruption: Bool = withLock { pausedByInterruption }
        guard wasByInterruption else { return }
        withLock { pausedByInterruption = false }
        let canResume: Bool = withLock { state == .paused && engine != nil }
        if canResume && shouldResume, let engine {
            engine.resumeRecording()
            withLock {
                elapsed.resume()
                state = .recording
            }
        }
        emit("interruptionEnd", DMPMap())
    }

    // MARK: finalize（delegate / 兜底定时器，幂等）

    private func finalizeRecording(success: Bool) {
        var proceed = false
        withLock {
            if !finalized {
                finalized = true
                state = .idle
                pausedByInterruption = false
                proceed = true
            }
        }
        cancelFallback()
        guard proceed else { return }
        // 引擎引用仅用于归档，释放交给 AVAudioRecorder 自身；这里置空避免悬挂。
        self.engine = nil
        let file = outputFile
        outputFile = nil
        let durationMs: Int64 = withLock { elapsed.stop() }
        let stopCallback: DMPBridgeCallback? = withLock {
            let pending = pendingStop
            pendingStop = nil
            return pending
        }
        if !RecorderStore.hasActiveRecorder() {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }

        if success, let file, FileManager.default.fileExists(atPath: file.path) {
            let attributes = try? FileManager.default.attributesOfItem(atPath: file.path)
            let fileSize = (attributes?[.size] as? NSNumber)?.intValue ?? 0
            let vPath = DMPFileUtil.vPathFromSandboxPath(sandboxPath: file.path, appId: appId)
            if let stopCallback {
                DMPContainerApi.invokeSuccess(callback: stopCallback, param: DMPMap([
                    "tempFilePath": vPath,
                    "duration": Int(durationMs),
                    "fileSize": fileSize,
                    "errMsg": "Recorder.stop:ok",
                ]))
            }
            // onStop 事件 payload 不带 errMsg（契约线协议，对齐安卓）。
            emit("stop", DMPMap([
                "tempFilePath": vPath,
                "duration": Int(durationMs),
                "fileSize": fileSize,
            ]))
        } else {
            if let file {
                try? FileManager.default.removeItem(at: file)
            }
            if let stopCallback {
                AudioAPI.fail(stopCallback, "Recorder.stop:fail no recorded data")
            }
            emit("error", DMPMap([
                "errCode": RecorderRules.ERR_SYSTEM,
                "errMsg": "Recorder.stop:fail no recorded data",
            ]))
        }
    }

    /// 录音中途引擎错误（编码失败等）：不经过 stop 通道直接收口（对齐安卓 onEngineError）。
    private func handleEngineError(_ detail: String) {
        var proceed = false
        withLock {
            if !finalized {
                finalized = true
                state = .idle
                pausedByInterruption = false
                proceed = true
            }
        }
        cancelFallback()
        guard proceed else { return }
        engine?.dispose()
        engine = nil
        outputFile = nil
        if !RecorderStore.hasActiveRecorder() {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
        let stopCallback: DMPBridgeCallback? = withLock {
            let pending = pendingStop
            pendingStop = nil
            return pending
        }
        // 在途 stop 回执也要结清，避免 JS 回调悬挂。
        if let stopCallback {
            AudioAPI.fail(stopCallback, "Recorder.stop:fail record error")
        }
        emit("error", DMPMap([
            "errCode": RecorderRules.ERR_SYSTEM,
            "errMsg": "Recorder.record:fail \(detail)",
        ]))
    }

    // MARK: 兜底 finalize（防 AVAudioRecorderDelegate 丢失导致 JS 悬挂）

    private func armFinalizeFallback() {
        cancelFallback()
        let work = DispatchWorkItem { [weak self] in
            self?.finalizeRecording(success: true)
        }
        fallback = work
        queue.asyncAfter(deadline: .now() + 2.5, execute: work)
    }

    private func cancelFallback() {
        fallback?.cancel()
        fallback = nil
    }

    // MARK: 清理

    func teardown() {
        queue.async { self.teardownNow() }
    }

    private func teardownNow() {
        cancelFallback()
        engine?.dispose()
        engine = nil
        outputFile = nil
        withLock {
            pendingStart = nil
            pendingStop = nil
            finalized = true
            state = .idle
            pausedByInterruption = false
            listeners.removeAll()
        }
        if !RecorderStore.hasActiveRecorder() {
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }
}

// MARK: - AVAudioRecorder 封装

private final class RecorderEngine: NSObject, AVAudioRecorderDelegate {
    private var recorder: AVAudioRecorder?
    private let queue: DispatchQueue
    private let onFinish: (Bool, String?) -> Void
    private let onEncodeError: (String) -> Void

    init(queue: DispatchQueue,
         onFinish: @escaping (Bool, String?) -> Void,
         onEncodeError: @escaping (String) -> Void) {
        self.queue = queue
        self.onFinish = onFinish
        self.onEncodeError = onEncodeError
        super.init()
    }

    /// aac → MPEG-4 AAC（.m4a 容器）；wav → LinearPCM 16-bit（.wav 容器）。
    /// AVAudioRecorder 按扩展名选容器，其他格式在 handler 已 fail 10004。
    func start(to url: URL, format: String, sampleRate: Int,
               channels: Int, encodeBitRate: Int, maxDurationMs: Int) throws {
        let settings: [String: Any]
        if format == "aac" {
            settings = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: Double(sampleRate),
                AVNumberOfChannelsKey: channels,
                AVEncoderBitRateKey: encodeBitRate,
            ]
        } else {
            settings = [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: Double(sampleRate),
                AVNumberOfChannelsKey: channels,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ]
        }
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.delegate = self
        self.recorder = recorder
        let started: Bool
        if maxDurationMs > 0 {
            started = recorder.record(forDuration: Double(maxDurationMs) / 1000.0)
        } else {
            started = recorder.record()
        }
        guard started else {
            self.recorder = nil
            throw AudioError("audio record start failed")
        }
    }

    func pauseRecording() {
        recorder?.pause()
    }

    func resumeRecording() {
        _ = recorder?.record()
    }

    func stop() {
        recorder?.stop()
    }

    func dispose() {
        recorder?.stop()
        recorder = nil
    }

    // MARK: AVAudioRecorderDelegate（派发回会话队列）

    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        queue.async { self.onFinish(flag, nil) }
    }

    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        let detail = error?.localizedDescription ?? "encode error"
        queue.async { self.onEncodeError(detail) }
    }
}

// MARK: - 麦克风权限

extension AudioAPI {

    /// 请求麦克风权限。iOS 17 起 requestRecordPermission 被 AVAudioApplication 取代，
    /// 旧 API 在新 SDK 仅告警不报错，为保证 iOS 14 部署目标直接走旧 API（后续可迁移）。
    static func requestMicPermission(completion: @escaping (Bool) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            completion(granted)
        }
    }
}
