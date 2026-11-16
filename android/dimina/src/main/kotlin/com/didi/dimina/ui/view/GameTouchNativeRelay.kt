package com.didi.dimina.ui.view

import android.view.Choreographer
import android.view.MotionEvent
import com.didi.dimina.core.JsCore
import com.didi.dimina.core.RperfNativeStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [game-touch] 游戏触摸快路（微维测/AB 期，整文件可随接线一起删除）。
 *
 * 背景：上轮输入审计 R3 确认游戏触摸延迟大头在 WebView renderer 主线程——DOM touch 事件
 * 排在每帧 GL 指令执行之后（renderer 帧槽位排队 p50 40-47ms）。本快路把 ACTION_* 在
 * 原生层直接打包转发给 QuickJS service（JsCore.postMessage(String)，线程安全），
 * 完全绕开 renderer 线程与 DOM。
 *
 * 契约（与 fe/packages/render/src/core/runtime.js createGameCanvas 的 sendGameTouch 逐字段一致，
 * service 直接把 body 当事件对象递给游戏回调）：
 *   jscore.postMessage(JSONObject()
 *     .put("type", "gameTouch")
 *     .put("target", "service")
 *     .put("body", body))
 *   body = { bridgeId, eventType("touchstart|touchmove|touchend|touchcancel"),
 *            touches:[{identifier,clientX,clientY,pageX,pageY,force}...],
 *            changedTouches:[...], timeStamp, __natWall }
 *
 * 开关：filesDir/dimina-file-system/<appId>/usr/perf-touch-native.txt，内容 '0' = 关闭
 * （不设委托、不注入能力标志，行为与旧版完全一致，A/B 基线臂）；缺失/其他值 = 开启（默认开）。
 * 开启时 Bridge.start()（页面加载后）evaluateJavascript 注入 window.__diminaNativeGameTouch=true，
 * fe render 侧据此跳过 DOM touch/mouse 监听注册。
 */

/** 与 fe serializeTouch 输出逐字段对应的触摸点（坐标已换算为 CSS px）。 */
internal class GameTouchPoint(
    val identifier: Int,
    val clientX: Double,
    val clientY: Double,
    val force: Double,
) {
    /** 全屏 fixed canvas：pageX = clientX、pageY = clientY。 */
    val pageX: Double get() = clientX
    val pageY: Double get() = clientY
}

/** 一次触摸事件的完整快照（已脱离 MotionEvent，供纯逻辑合帧器消费）。 */
internal class GameTouchSnapshot(
    val eventType: String,
    val touches: List<GameTouchPoint>,
    val changedTouches: List<GameTouchPoint>,
    /** DOM 语义的毫秒时间戳（MotionEvent.eventTime 为 ns，÷1e6 换算，来源 base=uptime）。 */
    val timeStampMs: Double,
    /** 事件到达原生的墙钟，供与 fe 侧 __domWall 对齐分析输入链路。 */
    val wallMs: Long,
)

/**
 * 按帧合帧器（纯逻辑，单线程约束：触摸与帧回调都在主线程）。
 *
 * ACTION_MOVE 采用 latest-wins：只保留最新快照并累计被折叠条数，由注入的
 * scheduleFrame（生产=Choreographer 下一帧）统一发出，≤1 条/帧；
 * touchstart/touchend/touchcancel 先 flush 挂起的 move 再立即发送（保序）。
 *
 * @param scheduleFrame 帧调度注入点（可测：测试里手动驱动）
 * @param send          出口注入点；第二参为本次消息折叠的 move 总条数（≥1）
 */
internal class GameTouchFrameBatcher(
    private val scheduleFrame: ((Runnable) -> Unit)?,
    private val send: (GameTouchSnapshot, Int) -> Unit,
) {
    private var pendingMove: GameTouchSnapshot? = null
    private var pendingCount = 0
    private var frameScheduled = false

    fun offer(snapshot: GameTouchSnapshot) {
        when (snapshot.eventType) {
            "touchmove" -> {
                if (pendingMove == null) {
                    pendingCount = 1 // 本帧第一条
                } else {
                    // latest-wins：上一条还没发走，直接覆盖并计一次折叠。
                    pendingCount++
                }
                pendingMove = snapshot
                if (!frameScheduled) {
                    frameScheduled = true
                    scheduleFrame?.invoke { onFrame() }
                }
            }
            "touchstart", "touchend", "touchcancel" -> {
                flushPendingMove()
                send(snapshot, 1)
            }
        }
    }

    /** Choreographer 帧回调：重置调度标记在前，防「空跑后标记卡死」导致后续 move 永不调度。 */
    fun onFrame() {
        frameScheduled = false
        val snapshot = pendingMove ?: return
        pendingMove = null
        send(snapshot, pendingCount)
        pendingCount = 0
    }

    /** DOWN/UP/CANCEL 前的保序 flush；已排定的帧回调照跑（空跑一次并重置标记）。 */
    fun flushPendingMove() {
        val snapshot = pendingMove ?: return
        pendingMove = null
        send(snapshot, pendingCount)
        pendingCount = 0
    }

    /** 快路摘除时丢掉未发出的挂起 move，防止幽灵触摸。 */
    fun reset() {
        pendingMove = null
        pendingCount = 0
    }
}

/** 快路 JSON 编码（纯逻辑，单测直接断言逐字段契约）。 */
internal object GameTouchCodec {
    fun pointJson(point: GameTouchPoint): JSONObject = JSONObject()
        .put("identifier", point.identifier)
        .put("clientX", point.clientX)
        .put("clientY", point.clientY)
        .put("pageX", point.pageX)
        .put("pageY", point.pageY)
        .put("force", point.force)

    private fun pointsJson(points: List<GameTouchPoint>): JSONArray =
        JSONArray().also { array -> points.forEach { array.put(pointJson(it)) } }

    fun bodyJson(snapshot: GameTouchSnapshot, bridgeId: String): JSONObject = JSONObject()
        .put("bridgeId", bridgeId)
        .put("eventType", snapshot.eventType)
        .put("touches", pointsJson(snapshot.touches))
        .put("changedTouches", pointsJson(snapshot.changedTouches))
        .put("timeStamp", snapshot.timeStampMs)
        .put("__natWall", snapshot.wallMs)

    /** jscore.postMessage(String) 的完整信封。 */
    fun envelopeJson(snapshot: GameTouchSnapshot, bridgeId: String): JSONObject = JSONObject()
        .put("type", "gameTouch")
        .put("target", "service")
        .put("body", bodyJson(snapshot, bridgeId))
}

/** 开关读取（纯 JVM 可测）：启动时读一次，触摸路径上零文件 IO。 */
internal object GameTouchRelaySwitch {
    const val FILE_NAME = "perf-touch-native.txt"

    /** 运行时门：仅 game 运行时 + 开关非 '0'。 */
    fun isEnabledFor(runtimeType: String, filesDir: File, appId: String): Boolean =
        runtimeType == "game" && isEnabled(filesDir, appId)

    /** 文件内容 '0' = 关闭；缺失/读取失败/其他值 = 开启（默认开，A/B 基线臂写 '0'）。 */
    fun isEnabled(filesDir: File, appId: String): Boolean = try {
        val file = File(filesDir, "dimina-file-system/$appId/usr/$FILE_NAME")
        if (!file.exists()) true else file.readText().trim() != "0"
    } catch (_: Throwable) {
        true
    }
}

/**
 * 原生快路委托本体：挂在 MiniProgramWebView.gameTouchDelegate 上（仅 game + 开关开）。
 * invoke 由 WebView 主线程调用；序列化走主线程（与触摸同线程，无锁），
 * postMessage(String) 内部落 JsCore 的 runtimeMessageQueue（线程安全）。
 */
internal class GameTouchNativeRelay(
    private val jscore: JsCore,
    private val bridgeId: String,
    /** WebView 所在屏幕密度：px → CSS px。 */
    private val density: Float,
) : (MotionEvent) -> Unit {

    private val batcher = GameTouchFrameBatcher(
        scheduleFrame = { runnable -> Choreographer.getInstance().postFrameCallback { runnable.run() } },
        send = { snapshot, coalesced -> dispatch(snapshot, coalesced) },
    )

    @Volatile
    private var disposed = false

    /** 桥销毁时摘除：丢弃挂起 move 并拒绝后续事件，防旧桥幽灵触摸。 */
    fun dispose() {
        disposed = true
        batcher.reset()
    }

    override fun invoke(event: MotionEvent) {
        if (disposed) return
        val snapshot = snapshotOf(event) ?: return
        batcher.offer(snapshot)
    }

    /** MotionEvent → DOM 语义快照（touches=全部指针存量，changedTouches=按事件语义的增量）。 */
    private fun snapshotOf(event: MotionEvent): GameTouchSnapshot? {
        val action = event.actionMasked
        val eventType = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "touchstart"
            MotionEvent.ACTION_MOVE -> "touchmove"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "touchend"
            MotionEvent.ACTION_CANCEL -> "touchcancel"
            else -> return null
        }
        val all = ArrayList<GameTouchPoint>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            all.add(pointAt(event, index))
        }
        val activeIndex = event.actionIndex.coerceIn(0, (event.pointerCount - 1).coerceAtLeast(0))
        val (touches, changedTouches) = when (action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> all to all
            MotionEvent.ACTION_POINTER_DOWN -> all to listOf(all[activeIndex])
            MotionEvent.ACTION_POINTER_UP -> all.filterIndexed { i, _ -> i != activeIndex } to listOf(all[activeIndex])
            else -> emptyList<GameTouchPoint>() to all // ACTION_UP / ACTION_CANCEL：存量已清空
        }
        return GameTouchSnapshot(
            eventType = eventType,
            touches = touches,
            changedTouches = changedTouches,
            timeStampMs = event.eventTime / 1_000_000.0,
            wallMs = System.currentTimeMillis(),
        )
    }

    private fun pointAt(event: MotionEvent, index: Int): GameTouchPoint = GameTouchPoint(
        identifier = event.getPointerId(index),
        clientX = (event.getX(index) / density).toDouble(),
        clientY = (event.getY(index) / density).toDouble(),
        force = event.getPressure(index).toDouble().coerceIn(0.0, 1.0),
    )

    private fun dispatch(snapshot: GameTouchSnapshot, coalesced: Int) {
        if (disposed) return
        // [game-touch] serialize/post 打点（ENABLED=false 时为空操作，整块可删）
        val serializeStartNs = System.nanoTime()
        val message = GameTouchCodec.envelopeJson(snapshot, bridgeId).toString()
        val serializeNs = System.nanoTime() - serializeStartNs
        val postStartNs = System.nanoTime()
        jscore.postMessage(message)
        val postNs = System.nanoTime() - postStartNs
        RperfNativeStats.recordTouch(serializeNs, postNs)
        if (coalesced > 1) RperfNativeStats.addTouchCoalesced(coalesced - 1)
    }
}
