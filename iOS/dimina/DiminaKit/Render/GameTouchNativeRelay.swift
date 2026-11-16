//
//  GameTouchNativeRelay.swift
//  dimina
//
//  [game-touch] iOS 游戏触摸快路（微维测/AB 期，整文件可随接线一起删除）。
//
//  移植自 Android GameTouchNativeRelay.kt（契约与设计权威，逐字段一致）。背景：上轮
//  输入审计 R3 确认游戏触摸延迟大头在 WebView 渲染主线程——DOM touch 事件排在每帧
//  GL 指令执行之后（renderer 帧槽位排队 p50 40-47ms）。本快路把 UIKit 触摸在原生层
//  直接打包转发给逻辑层 JS 引擎（DMPEngine.enqueueMessage，线程安全），完全绕开
//  WKWebView 内容进程与 DOM。
//
//  消息契约（与 fe/packages/render/src/core/runtime.js createGameCanvas 的 sendGameTouch
//  逐字段一致，service 直接把 body 当事件对象递给游戏回调）：
//    DiminaServiceBridge.onMessage({
//      type: "gameTouch", target: "service",
//      body: { bridgeId, eventType("touchstart|touchmove|touchend|touchcancel"),
//              touches:[{identifier,clientX,clientY,pageX,pageY,force}...],
//              changedTouches:[...], timeStamp, __natWall } })
//  body 上未知字段由 service 原样透传给游戏回调；__natWall 为事件到达原生的墙钟，
//  与 service 侧 globalThis.__gtAt（message.js handleMsg 入口打点）配对可算输入 p50。
//
//  开关：Documents/Dimina/<appId>/usr/perf-touch-native.txt，内容 '0' = 关闭
//  （不挂手势、不注入能力标志，行为与旧版完全一致，A/B 基线臂）；缺失/其他值 = 开启
//  （默认开）。开启时在 pageFrame document-start 注入 window.__diminaNativeGameTouch=true
//  （比安卓的 evaluateJavascript 更早：先于渲染层 runtime 求值），fe render 据此跳过
//  DOM touch/mouse 监听注册。
//
//  与安卓的共存差异：安卓 dispatchTouchEvent 消费掉全部事件（WebView 完全看不到）；
//  iOS 用观察型手势（cancelsTouchesInView=false + 与所有手势并行识别），WKWebView
//  内容进程照常收到完整触摸流——但快路开启时 fe render 不注册任何 DOM 监听，慢路
//  无消费者，互不干扰；开关关闭时本文件零挂载，渲染层行为与旧版完全一致。
//

import Foundation
import UIKit
import WebKit

// MARK: - 快路开关（启动时读一次，触摸路径上零文件 IO）

enum DMPGameTouchRelaySwitch {
    static let fileName = "perf-touch-native.txt"

    /// 运行时门：仅 game 运行时 + 开关非 '0'（与 DMPApp.swift 的 runtimeType 判定同口径）。
    static func isEnabledFor(runtimeType: String?, appId: String) -> Bool {
        guard runtimeType == "game" else { return false }
        return isEnabled(appId: appId)
    }

    /// 文件内容 '0' = 关闭；缺失/读取失败/其他值 = 开启（默认开，A/B 基线臂写 '0'）。
    static func isEnabled(appId: String) -> Bool {
        guard !appId.isEmpty else { return true }
        let path = (DMPSandboxManager.sandboxPath() as NSString)
            .appendingPathComponent("\(appId)/usr/\(fileName)")
        guard let content = try? String(contentsOfFile: path, encoding: .utf8) else {
            return true
        }
        return content.trimmingCharacters(in: .whitespacesAndNewlines) != "0"
    }
}

// MARK: - 快路数据形状

/// 与 fe serializeTouch 输出逐字段对应的触摸点（坐标为 CSS px）。
/// WKWebView 的 UIKit point 与 CSS px 1:1（pageFrame 固定 initial-scale=1、
/// user-scalable=no、容器不做 zoom 改写），无需安卓的 density 换算。
struct DMPGameTouchPoint {
    let identifier: Int
    let clientX: Double
    let clientY: Double
    let force: Double

    /// 全屏 fixed canvas：pageX = clientX、pageY = clientY（与安卓 Relay 一致）。
    var pageX: Double { clientX }
    var pageY: Double { clientY }
}

/// 一次触摸事件的完整快照（已脱离 UITouch，供纯逻辑合帧器消费）。
struct DMPGameTouchSnapshot {
    let eventType: String
    let touches: [DMPGameTouchPoint]
    let changedTouches: [DMPGameTouchPoint]
    /// DOM 语义的毫秒时间戳（UITouch.timestamp 为 uptime 秒 ×1000，与安卓
    /// MotionEvent.eventTime/1e6 同口径：uptime 时基，非页面打开时基）。
    let timeStampMs: Double
    /// 事件到达原生的墙钟，供与 service 侧 __gtAt 对齐分析输入链路（p50 = 中位数）。
    let wallMs: Int64
}

// MARK: - 按帧合帧器（纯逻辑，主线程约束：触摸与 CADisplayLink 帧回调都在主线程）

protocol DMPGameTouchFrameBatcherSink: AnyObject {
    /// 第二参为本次消息折叠的 move 总条数（≥1）。
    func frameBatcher(_ batcher: DMPGameTouchFrameBatcher, didFlush snapshot: DMPGameTouchSnapshot, coalescedMoveCount: Int)
}

/// touchmove 采用 latest-wins：只保留最新快照并累计被折叠条数，由 CADisplayLink
/// 帧回调统一发出（≤1 条/帧，60/120Hz 跟屏自适应，对应安卓的 Choreographer）；
/// touchstart/touchend/touchcancel 先 flush 挂起的 move 再立即发送（保序）。
final class DMPGameTouchFrameBatcher {
    weak var sink: DMPGameTouchFrameBatcherSink?

    private var pendingMove: DMPGameTouchSnapshot?
    private var pendingCount = 0
    private var frameScheduled = false
    private var displayLink: CADisplayLink?

    func offer(_ snapshot: DMPGameTouchSnapshot) {
        switch snapshot.eventType {
        case "touchmove":
            pendingCount = pendingMove == nil ? 1 : pendingCount + 1
            pendingMove = snapshot
            scheduleFrameIfNeeded()
        case "touchstart", "touchend", "touchcancel":
            flushPendingMove()
            sink?.frameBatcher(self, didFlush: snapshot, coalescedMoveCount: 1)
        default:
            break
        }
    }

    /// CADisplayLink 帧回调：重置调度标记在前，防「空跑后标记卡死」导致后续 move
    /// 永不调度（与安卓 GameTouchFrameBatcher.onFrame 一致）。
    @objc func onFrame() {
        frameScheduled = false
        displayLink?.isPaused = true
        guard let snapshot = pendingMove else { return }
        pendingMove = nil
        sink?.frameBatcher(self, didFlush: snapshot, coalescedMoveCount: pendingCount)
        pendingCount = 0
    }

    /// DOWN/UP/CANCEL 前的保序 flush；已排定的帧回调照跑（空跑一次并重置标记）。
    func flushPendingMove() {
        guard let snapshot = pendingMove else { return }
        pendingMove = nil
        sink?.frameBatcher(self, didFlush: snapshot, coalescedMoveCount: pendingCount)
        pendingCount = 0
    }

    /// 快路摘除时丢掉未发出的挂起 move 并停掉帧回调，防止幽灵触摸。
    func reset() {
        pendingMove = nil
        pendingCount = 0
        displayLink?.invalidate()
        displayLink = nil
        frameScheduled = false
    }

    private func scheduleFrameIfNeeded() {
        guard !frameScheduled else { return }
        frameScheduled = true
        if displayLink == nil {
            // .common 模式：WKWebView 滚动/手势跟踪态下帧回调照常发。
            let link = CADisplayLink(target: self, selector: #selector(onFrame))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }
        displayLink?.isPaused = false
    }
}

// MARK: - 快路信封编码（与安卓 GameTouchCodec 逐字段一致；service 侧零改动）

enum DMPGameTouchCodec {
    static func envelope(_ snapshot: DMPGameTouchSnapshot, bridgeId: Int) -> [String: Any] {
        func pointDict(_ point: DMPGameTouchPoint) -> [String: Any] {
            [
                "identifier": point.identifier,
                "clientX": point.clientX,
                "clientY": point.clientY,
                "pageX": point.pageX,
                "pageY": point.pageY,
                "force": point.force,
            ]
        }
        return [
            "type": "gameTouch",
            "target": "service",
            "body": [
                "bridgeId": bridgeId,
                "eventType": snapshot.eventType,
                "touches": snapshot.touches.map(pointDict),
                "changedTouches": snapshot.changedTouches.map(pointDict),
                "timeStamp": snapshot.timeStampMs,
                "__natWall": snapshot.wallMs,
            ],
        ]
    }
}

// MARK: - 原生快路本体

/// 观察型手势：挂在游戏态 WKWebView 上（仅 game + 开关开）。
/// cancelsTouchesInView=false、delays* =false、与所有手势并行识别 → 本快路只读不消费，
/// 绝不打断 WKWebView 内部手势与渲染层；快路自身异常（dispose/开关关闭）即零挂载，
/// 行为与旧版完全一致。
///
/// 生命周期：由挂载方（DMPPageController）在页面拆毁时调 dispose()——丢挂起 move +
/// 摘手势（removeGestureRecognizer 只摘本实例，防 WebView 复用场景误清新快路）。
/// 所有触摸/帧回调都在主线程；engine.enqueueMessage 内部落 JS 引擎串行队列（线程安全）。
final class DMPGameTouchNativeRelay: UIGestureRecognizer, UIGestureRecognizerDelegate, DMPGameTouchFrameBatcherSink {

    /// UITouch 没有稳定数字 id，序列内维持 weak 记录表换算 DOM identifier。
    private struct ActiveTouch {
        weak var touch: UITouch?
        let identifier: Int
    }

    private weak var engine: DMPEngine?
    private let bridgeId: Int
    private weak var hostWebView: WKWebView?
    private let batcher = DMPGameTouchFrameBatcher()
    private var activeTouches: [ActiveTouch] = []
    /// 浏览器语义的 identifier 在页面生命周期内单调递增，永不复用（防新旧序列撞号）。
    private var nextIdentifier = 0
    private var disposed = false

    /// 真机现场观察用计数（纯内存，不落盘；p50 用 __natWall↔__gtAt 配对量）。
    private(set) var sentMessageCount = 0
    private(set) var coalescedMoveCount = 0

    /// 仅 game 运行时且开关非 '0' 时可构造；返回 nil = 不启用（非 game 页绝不改变现有行为）。
    init?(app: DMPApp?, webview: DMPWebview) {
        guard let app,
              let bundleAppConfig = app.getBundleAppConfig(),
              DMPGameTouchRelaySwitch.isEnabledFor(
                  runtimeType: bundleAppConfig.runtimeType,
                  appId: app.getAppId())
        else { return nil }

        self.engine = app.service?.getEngine()
        self.bridgeId = webview.getWebViewId()
        self.hostWebView = webview.getWebView()
        super.init(target: nil, action: nil)
        batcher.sink = self

        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    /// 挂载点：在 pageFrame 加载前调用（WKWebView 创建之后、loadPageFrame 之前）。
    /// document-start 用户脚本保证 window.__diminaNativeGameTouch=true 先于渲染层
    /// runtime 求值，fe render 的 createGameCanvas 据此跳过 DOM touch/mouse 监听注册。
    /// 用户脚本随 WebView 池的 prepareForReuse/cleanupWebView（removeAllUserScripts）
    /// 一并清除，不会跨 App 泄漏。
    @discardableResult
    static func attachIfEnabled(app: DMPApp?, webview: DMPWebview) -> DMPGameTouchNativeRelay? {
        assert(Thread.isMainThread, "game-touch relay must be attached on the main thread")
        guard let relay = DMPGameTouchNativeRelay(app: app, webview: webview) else {
            return nil
        }
        let flagScript = WKUserScript(
            source: "window.__diminaNativeGameTouch=true;",
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        webview.getWebView().configuration.userContentController.addUserScript(flagScript)
        webview.getWebView().addGestureRecognizer(relay)
        DMPLogger.debug("[game-touch] native relay attached, webViewId=\(webview.getWebViewId())")
        return relay
    }

    /// 桥销毁即摘快路：丢挂起 move + 摘手势 + 拒后续事件（防旧桥幽灵触摸）。
    func dispose() {
        guard !disposed else { return }
        disposed = true
        batcher.reset()
        hostWebView?.removeGestureRecognizer(self)
        hostWebView = nil
    }

    deinit {
        // 兜底：正常生命周期由挂载方 destroyWebView 调 dispose()（丢挂起 move +
        // 摘手势 + 停帧回调），防 CADisplayLink→batcher 强引用环泄漏。
        // 页面控制器生命周期都在主线程，主线程上直接收尾即可。
        if Thread.isMainThread {
            dispose()
        }
    }

    // MARK: 触摸捕获 → DOM 语义快照

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesBegan(touches, with: event)
        guard !disposed, hostWebView != nil else { return }
        // touchesBegan 的 touches = 本次新增的触点（对应安卓 ACTION_DOWN/POINTER_DOWN）。
        for touch in touches {
            activeTouches.append(ActiveTouch(touch: touch, identifier: nextIdentifier))
            nextIdentifier += 1
        }
        if state == .possible { state = .began }
        offer("touchstart", changedTouches: touches, excludeFromAll: [])
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesMoved(touches, with: event)
        guard !disposed, hostWebView != nil else { return }
        if state == .began { state = .changed }
        offer("touchmove", changedTouches: touches, excludeFromAll: [])
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesEnded(touches, with: event)
        guard !disposed, hostWebView != nil else { return }
        offer("touchend", changedTouches: touches, excludeFromAll: touches)
        removeRecords(of: touches)
        if activeTouches.isEmpty { state = .ended }
        // 仍有触点在屏（多指先后抬起）：保持 .changed，快路继续观察剩余序列。
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesCancelled(touches, with: event)
        guard !disposed, hostWebView != nil else { return }
        offer("touchcancel", changedTouches: touches, excludeFromAll: touches)
        removeRecords(of: touches)
        if activeTouches.isEmpty { state = .cancelled }
    }

    // MARK: UIGestureRecognizerDelegate —— 只观察，不与任何手势竞争

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
    ) -> Bool {
        true
    }

    // MARK: DMPGameTouchFrameBatcherSink —— 直投逻辑层

    func frameBatcher(
        _ batcher: DMPGameTouchFrameBatcher,
        didFlush snapshot: DMPGameTouchSnapshot,
        coalescedMoveCount: Int
    ) {
        dispatch(snapshot, coalesced: coalescedMoveCount)
    }

    // MARK: Private

    private func dispatch(_ snapshot: DMPGameTouchSnapshot, coalesced: Int) {
        guard !disposed, let engine else { return }
        engine.enqueueMessage(DMPGameTouchCodec.envelope(snapshot, bridgeId: bridgeId))
        sentMessageCount += 1
        if coalesced > 1 { coalescedMoveCount += coalesced - 1 }
    }

    private func offer(
        _ eventType: String,
        changedTouches: Set<UITouch>,
        excludeFromAll: Set<UITouch>
    ) {
        guard let host = hostWebView else { return }
        // touches = 全部触点存量（end/cancel 排除本次抬起的），changedTouches = 本次事件增量
        // ——与安卓 snapshotOf 的 ACTION_*/POINTER_* 语义逐项对应。
        var allPoints: [DMPGameTouchPoint] = []
        var wallMs: Int64 = 0
        var timeStampMs: Double = 0
        for record in activeTouches where record.touch.map({ !excludeFromAll.contains($0) }) ?? false {
            allPoints.append(point(of: record.touch!, in: host))
        }
        var changedPoints: [DMPGameTouchPoint] = []
        for touch in changedTouches {
            changedPoints.append(point(of: touch, in: host))
            wallMs = Int64(Date().timeIntervalSince1970 * 1000)
            timeStampMs = max(timeStampMs, touch.timestamp * 1000)
        }
        guard !changedPoints.isEmpty else { return }
        batcher.offer(DMPGameTouchSnapshot(
            eventType: eventType,
            touches: allPoints,
            changedTouches: changedPoints,
            timeStampMs: timeStampMs,
            wallMs: wallMs
        ))
    }

    private func point(of touch: UITouch, in host: WKWebView) -> DMPGameTouchPoint {
        let location = touch.location(in: host)
        let maximumForce = touch.maximumPossibleForce
        let force = maximumForce > 0 ? Double(touch.force / maximumForce) : 0.0
        return DMPGameTouchPoint(
            identifier: identifier(of: touch),
            clientX: Double(location.x),
            clientY: Double(location.y),
            force: min(max(force, 0.0), 1.0)
        )
    }

    private func identifier(of touch: UITouch) -> Int {
        activeTouches.first(where: { $0.touch === touch })?.identifier ?? 0
    }

    private func removeRecords(of touches: Set<UITouch>) {
        activeTouches.removeAll { record in
            record.touch.map { touches.contains($0) } ?? true
        }
    }
}
