package com.didi.dimina.engine.qjs

/**
 * [rperf] engine_qjs 侧时序统计器（性能微维测期专用，整块可删）。
 *
 * engine_qjs 不依赖 dimina 模块，因此这里只做 public 的轻量环形缓冲 + [drain]：
 * dimina 的 `RperfNativeStats` 每 5s 反向依赖读取本对象，把以下三段耗时合并进同一份
 * `filesDir/rperf_native.json`（新增 engine_* 字段，不改动已有字段）。
 *
 * 三段（都只统计 body.name == "canvasNodeFlush" 的帧，由 [QuickJSEngine.publishCanvasFromJS] 过滤后调用）：
 *  - serializeNs：原生 `dimina::canvas::SerializeCanvasBody` 耗时——即【JS 对象 → JSON 字符串】
 *                 序列化 + 二进制 buffer 打包段，在 QuickJS 线程上、进入 Kotlin 之前完成。
 *  - jniNs：原生→Kotlin 的参数编组段——UTF-16 解码、`NewString`、`NewByteArray` 拷贝、JNI 方法查找。
 *  - parseNs：Kotlin `JSONObject(json)` 把 JSON 字符串 parse 成 JSONObject 的耗时。
 *
 * 所有方法吞掉异常，任何情况下不影响渲染。`ENABLED=false` 时 record 为空操作。
 */
object RperfEngineStats {
    // [rperf] 微维测开关：默认开。整体关闭只改这里（或删掉本文件与各处 // [rperf] 引用块）。
    const val ENABLED = true
    private const val MAX_SAMPLES = 8192

    /** [drain] 返回的一窗原始样本（各列已升序，由调用方算百分位）。 */
    class Snapshot internal constructor(
        val serializeNs: List<Long>,
        val jniNs: List<Long>,
        val parseNs: List<Long>,
        val jsonChars: List<Int>,
        val dropped: Int,
    ) {
        val n: Int get() = parseNs.size
    }

    private class Sample(
        val serializeNs: Long,
        val jniNs: Long,
        val parseNs: Long,
        val jsonChars: Int,
    )

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private var dropped = 0

    /** QuickJS 线程调用：一帧一条。 */
    fun record(serializeNs: Long, jniNs: Long, parseNs: Long, jsonChars: Int) {
        if (!ENABLED) return
        try {
            synchronized(lock) {
                if (samples.size >= MAX_SAMPLES) {
                    dropped++
                    return
                }
                samples.addLast(
                    Sample(
                        serializeNs.coerceAtLeast(0L),
                        jniNs.coerceAtLeast(0L),
                        parseNs.coerceAtLeast(0L),
                        jsonChars,
                    )
                )
            }
        } catch (_: Throwable) {
            // 打点永不影响渲染
        }
    }

    /** dimina 汇总线程调用：取走并清空当前窗口。 */
    fun drain(): Snapshot {
        val taken: List<Sample>
        val droppedNow: Int
        synchronized(lock) {
            taken = samples.toList()
            samples.clear()
            droppedNow = dropped
            dropped = 0
        }
        if (taken.isEmpty()) {
            return Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), droppedNow)
        }
        return Snapshot(
            taken.map { it.serializeNs }.sorted(),
            taken.map { it.jniNs }.sorted(),
            taken.map { it.parseNs }.sorted(),
            taken.map { it.jsonChars }.sorted(),
            droppedNow,
        )
    }
}
