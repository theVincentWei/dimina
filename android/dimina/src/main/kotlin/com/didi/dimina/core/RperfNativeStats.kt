package com.didi.dimina.core

import com.didi.dimina.engine.qjs.RperfEngineStats
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.round

/**
 * [rperf] 原生三段打点统计器——性能微维测期专用，整文件可整体删除。
 *
 * 只对 service→render 的 canvasNodeFlush 消息采样（每帧约 330 条 GL 指令 / 约 72KB 紧凑字符串），
 * 每 5s 汇总 p50/p90/max 并原子写 filesDir/rperf_native.json（先写 .tmp 再 rename，
 * 拉取时不会读到半文件）。真机 logcat DEBUG 被系统过滤，因此不依赖 logcat，
 * 由 lead 用 `adb run-as <pkg> cat files/rperf_native.json` 拉取。
 *
 * 字段含义（全部毫秒，除注明；p50/p90/max 取窗口内样本的 nearest-rank 百分位）：
 *  - handler_ms：Bridge.messagePublish 进入 → 返回。原生 handler 处理一帧的总耗时，
 *                包含 json_ms + packet_ms + post_ms + buffers 剥离/校验等残差。
 *  - json_ms：CanvasBinaryChannel.send 内 header = JSONObject().put("body", body).toString()
 *             这一遍 JSON 序列化（+ toByteArray），即任务里的 b 段。
 *  - packet_ms：header + 二进制 buffers 拷贝进 ByteBuffer 的耗时。三段之外的单列项：
 *               它发生在 handler 内、却既不算 JSON 也不算投递，用来把 handler 残差拆净。
 *  - post_ms：WebMessagePortCompat.postMessage 单次调用耗时（投递到 WebView 通道，
 *             不等待 ack），即任务里的 c 段。
 *  - ops_chars：该帧 operationsCompact 字符串的字符数（≈ 桥上 payload 体量，72KB 量级）。
 *  - late_admit_n：本窗口内「handler 返回后才完成准入」的帧数。大于 0 表示 Canvas 通道
 *                 积压/饱和，此时 JS 侧 send_ms 里的等待时间不能全算进 JNI 段。
 *  - n / samples_total / windows / dropped / fallback_n / ts / uptime_ms / win_ms：窗口元信息。
 *
 * engine_qjs 反向依赖字段（由 RperfEngineStats.drain() 合并，见 engine_qjs/RperfEngineStats.kt）：
 *  - engine_serialize_ms：原生 `SerializeCanvasBody` —— JS 对象 → JSON 字符串序列化 + 二进制 buffer 打包。
 *  - engine_jni_ms：原生→Kotlin 参数编组（UTF-16 解码 + NewString + NewByteArray 拷贝 + 方法查找）。
 *  - engine_parse_ms：Kotlin `JSONObject(json)` 把 JSON 字符串 parse 成 JSONObject。
 *  - engine_json_chars / engine_n / engine_dropped：serialized JSON 字符数 / engine 样本数 / 丢样本数。
 *
 * 拆段用法：a（JNI 交叉 + 原生绑定序列化 + parse + 主线程排队）
 *          ≈ JS 侧 send_ms − handler_ms；再用 engine_serialize_ms + engine_jni_ms + engine_parse_ms 把 a 拆开，
 *          剩下的是 JS 侧编码 + 主线程排队 + 准入等待（准入等待用 late_admit_n 判别）。
 *          handler 内部再拆成 json_ms / packet_ms / post_ms / 残差。
 */
internal object RperfNativeStats {
    // [rperf] 微维测开关：默认开。整体关闭只改这里（或删掉本文件与各处 // [rperf] 引用块）。
    private const val ENABLED = true
    private const val WINDOW_MS = 5_000L
    private const val FILE_NAME = "rperf_native.json"
    private const val FLUSH_NAME = "canvasNodeFlush"
    private const val MAX_SAMPLES_PER_WINDOW = 4096
    private const val NS_PER_MS = 1_000_000.0

    /** 一帧一条，挂在主线程 ThreadLocal 上（handler 与 channel.send 在同一调用栈）。 */
    class Probe internal constructor() {
        internal var eligible = false
        internal var entryNs = 0L
        internal var jsonNs = 0L
        internal var packetNs = 0L
        internal var postNs = 0L
        internal var opsChars = 0
        internal var fallback = false
        internal var finished = false
    }

    private class Sample(
        val handlerNs: Long,
        val jsonNs: Long,
        val packetNs: Long,
        val postNs: Long,
        val opsChars: Int,
        val fallback: Boolean,
    )

    private val probe = ThreadLocal<Probe>()
    private val lock = Any()
    private val window = ArrayDeque<Sample>()
    private val lateAdmit = AtomicInteger(0)
    private var dropped = 0
    private var total = 0L
    private var windows = 0L

    // [rperf] BEGIN game touch 快路段（微维测期，整块可删）：serialize/post 耗时 + 条数
    private class TouchSample(val serializeNs: Long, val postNs: Long)
    private val touchLock = Any()
    private val touchWindow = ArrayDeque<TouchSample>()
    private var touchDropped = 0
    private var touchCoalesced = 0L
    // [rperf] END

    @Volatile
    private var dir: File? = null
    @Volatile
    private var scheduler: ScheduledExecutorService? = null
    private val bootNs = System.nanoTime()

    /** 幂等挂载：filesDir 由 Bridge 传入（parent.filesDir）。 */
    fun attach(filesDir: File) {
        if (!ENABLED) return
        try {
            if (dir != null) return
            dir = filesDir
            // 立刻落一个 n=0 的探活文件，便于确认打点链路已生效。
            writeWindow(emptyList(), 0)
            scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "rperf-native-stats").apply { isDaemon = true }
            }.also { exec ->
                exec.scheduleWithFixedDelay({ flush() }, WINDOW_MS, WINDOW_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: Throwable) {
            // 打点永不影响渲染
        }
    }

    /** messagePublish 入口调用，返回当前线程的帧级 probe（ENABLED=false 时恒为 null）。 */
    fun open(): Probe? {
        if (!ENABLED) return null
        return try {
            val p = probe.get() ?: Probe().also { probe.set(it) }
            p.eligible = false
            p.entryNs = System.nanoTime()
            p.jsonNs = 0L
            p.packetNs = 0L
            p.postNs = 0L
            p.opsChars = 0
            p.fallback = false
            p.finished = false
            p
        } catch (_: Throwable) {
            null
        }
    }

    /** 标记本帧是否为 canvasNodeFlush（并顺带记录 payload 字符数）。 */
    fun markFlush(p: Probe?, target: String?, body: JSONObject?) {
        if (p == null || target != "render" || body == null) return
        try {
            if (body.optString("name") != FLUSH_NAME) return
            p.eligible = true
            p.opsChars = body.optJSONObject("params")?.optString("operationsCompact")?.length ?: 0
        } catch (_: Throwable) {
        }
    }

    /** CanvasBinaryChannel.send/post 内取当前帧 probe；非 flush 帧返回 null（零样本污染）。 */
    fun current(): Probe? {
        if (!ENABLED) return null
        return try {
            probe.get()?.takeIf { it.eligible && !it.finished }
        } catch (_: Throwable) {
            null
        }
    }

    fun eligible(p: Probe?): Boolean = p != null && p.eligible && !p.finished

    fun addJson(p: Probe?, ns: Long) {
        if (p != null && ns > 0) p.jsonNs += ns
    }

    fun addPacket(p: Probe?, ns: Long) {
        if (p != null && ns > 0) p.packetNs += ns
    }

    fun addPost(p: Probe?, ns: Long) {
        if (p != null && ns > 0) p.postNs += ns
    }

    fun markFallback(p: Probe?) {
        p?.fallback = true
    }

    /** publish 的准入回调被触发时调用：handler 已返回才算「延迟准入」（通道饱和信号）。 */
    fun notifyAdmitted(p: Probe?) {
        if (p == null) return
        try {
            if (p.finished) lateAdmit.incrementAndGet()
        } catch (_: Throwable) {
        }
    }

    /** messagePublish 返回前收尾：出样本。 */
    fun finish(p: Probe?) {
        if (p == null) return
        try {
            if (!p.eligible) return
            val handlerNs = System.nanoTime() - p.entryNs
            p.finished = true
            record(Sample(handlerNs, p.jsonNs, p.packetNs, p.postNs, p.opsChars, p.fallback))
        } catch (_: Throwable) {
        } finally {
            p.eligible = false
        }
    }

    private fun record(sample: Sample) {
        synchronized(lock) {
            if (window.size >= MAX_SAMPLES_PER_WINDOW) {
                dropped++
                return
            }
            window.addLast(sample)
        }
    }

    /** [rperf] game touch 快路样本：单条消息的 JSON 序列化与 jscore.postMessage 耗时（可删）。 */
    fun recordTouch(serializeNs: Long, postNs: Long) {
        if (!ENABLED) return
        try {
            synchronized(touchLock) {
                if (touchWindow.size >= MAX_SAMPLES_PER_WINDOW) {
                    touchDropped++
                    return
                }
                touchWindow.addLast(TouchSample(serializeNs, postNs))
            }
        } catch (_: Throwable) {
        }
    }

    /** [rperf] 合帧器折叠掉的 touchmove 条数（suppressed，与 touch_n 相加=事件总量）。 */
    fun addTouchCoalesced(count: Int) {
        if (!ENABLED || count <= 0) return
        try {
            synchronized(touchLock) { touchCoalesced += count }
        } catch (_: Throwable) {
        }
    }


    private fun flush() {
        try {
            val snapshot: List<Sample>
            val droppedNow: Int
            val lateNow: Int
            synchronized(lock) {
                snapshot = window.toList()
                window.clear()
                droppedNow = dropped
                dropped = 0
                windows++
                total += snapshot.size
            }
            lateNow = lateAdmit.getAndSet(0)
            // [rperf] game touch 快路段一窗样本（可删）
            val touchSnapshot: List<TouchSample>
            val touchDroppedNow: Int
            val touchCoalescedNow: Long
            synchronized(touchLock) {
                touchSnapshot = touchWindow.toList()
                touchWindow.clear()
                touchDroppedNow = touchDropped
                touchDropped = 0
                touchCoalescedNow = touchCoalesced
                touchCoalesced = 0
            }
            // [rperf] 反向依赖 engine_qjs：取走 engine 侧序列化/JNI/parse 一窗样本（微维测期，整块可删）
            val engineSnapshot = RperfEngineStats.drain()
            writeWindow(snapshot, droppedNow, lateNow, engineSnapshot, touchSnapshot, touchDroppedNow, touchCoalescedNow)
        } catch (_: Throwable) {
            // 打点永不影响渲染
        }
    }

    private fun writeWindow(
        samples: List<Sample>,
        droppedNow: Int = 0,
        lateAdmitNow: Int = 0,
        engine: RperfEngineStats.Snapshot? = null,
        touch: List<TouchSample> = emptyList(),
        touchDroppedNow: Int = 0,
        touchCoalescedNow: Long = 0L,
    ) {
        val target = dir ?: return
        try {
            val handlers = samples.map { it.handlerNs }.sorted()
            val jsons = samples.map { it.jsonNs }.sorted()
            val packets = samples.map { it.packetNs }.sorted()
            val posts = samples.map { it.postNs }.sorted()
            val chars = samples.map { it.opsChars }.sorted()
            val json = JSONObject()
            json.put("version", 1)
            json.put("enabled", ENABLED)
            json.put("win_ms", WINDOW_MS)
            json.put("ts", System.currentTimeMillis())
            json.put("uptime_ms", round((System.nanoTime() - bootNs) / NS_PER_MS * 1000.0) / 1000.0)
            json.put("n", samples.size)
            json.put("samples_total", total)
            json.put("windows", windows)
            json.put("dropped", droppedNow)
            json.put("fallback_n", samples.count { it.fallback })
            json.put("late_admit_n", lateAdmitNow)
            putNsStats(json, "handler", handlers)
            putNsStats(json, "json", jsons)
            putNsStats(json, "packet", packets)
            putNsStats(json, "post", posts)
            json.put("ops_chars_p50", percentileInt(chars, 0.5))
            json.put("ops_chars_max", percentileInt(chars, 1.0))
            // [rperf] engine_qjs 侧三段（序列化 / JNI 编组 / parse），微维测期字段，整块可删
            json.put("engine_n", engine?.n ?: 0)
            json.put("engine_dropped", engine?.dropped ?: 0)
            putNsStats(json, "engine_serialize", engine?.serializeNs ?: emptyList())
            putNsStats(json, "engine_jni", engine?.jniNs ?: emptyList())
            putNsStats(json, "engine_parse", engine?.parseNs ?: emptyList())
            json.put("engine_json_chars_p50", percentileInt(engine?.jsonChars ?: emptyList(), 0.5))
            json.put("engine_json_chars_max", percentileInt(engine?.jsonChars ?: emptyList(), 1.0))
            // [rperf] BEGIN game touch 快路段（可删）：条数 + serialize/post 三列
            json.put("touch_n", touch.size)
            json.put("touch_dropped", touchDroppedNow)
            json.put("touch_coalesced_n", touchCoalescedNow)
            putNsStats(json, "touch_serialize", touch.map { it.serializeNs })
            putNsStats(json, "touch_post", touch.map { it.postNs })
            // [rperf] END
            writeAtomically(target, json.toString())
        } catch (_: Throwable) {
            // 打点永不影响渲染
        }
    }

    private fun putNsStats(json: JSONObject, prefix: String, sortedNs: List<Long>) {
        json.put("${prefix}_p50_ms", percentileNs(sortedNs, 0.5))
        json.put("${prefix}_p90_ms", percentileNs(sortedNs, 0.9))
        json.put("${prefix}_max_ms", percentileNs(sortedNs, 1.0))
    }

    // nearest-rank 百分位：idx = min(n-1, floor(p*n))；毫秒、保留 3 位小数。
    private fun percentileNs(sortedNs: List<Long>, p: Double): Any {
        if (sortedNs.isEmpty()) return JSONObject.NULL
        val idx = min(sortedNs.size - 1, (p * sortedNs.size).toInt())
        return round(sortedNs[idx] / NS_PER_MS * 1000.0) / 1000.0
    }

    private fun percentileInt(sorted: List<Int>, p: Double): Any {
        if (sorted.isEmpty()) return JSONObject.NULL
        val idx = min(sorted.size - 1, (p * sorted.size).toInt())
        return sorted[idx]
    }

    /** 先写 tmp 再 rename，避免 adb 拉取时读到半文件。 */
    private fun writeAtomically(dir: File, text: String) {
        val dst = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) {
                // 仍失败则至少保留 tmp，lead 可退而拉 .tmp
                return
            }
        }
    }
}
