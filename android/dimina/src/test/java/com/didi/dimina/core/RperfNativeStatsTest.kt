package com.didi.dimina.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [rperf] 原生三段打点统计器自检（微维测期，可随 RperfNativeStats 一起删除）。
 *
 * 断言统计器真正可跑的那部分纯逻辑：只对 canvasNodeFlush 采样、5s 窗口聚合的
 * nearest-rank p50/p90/max、payload 字符数、延迟准入计数，以及原子写文件（tmp→rename）。
 * handler_ms 是真实 System.nanoTime 测得的值，无法注入固定值，只断言单调关系。
 */
class RperfNativeStatsTest {

    @Test
    fun `windows only sample canvasNodeFlush and write atomic json`() {
        // 临时目录放在模块 build/ 下（沙箱可写，且 JVM 单测的 cwd 就是模块目录）。
        val dir = File("build/rperf-native-test").apply { deleteRecursively(); mkdirs() }

        // attach 立刻落一个 n=0 的探活文件。
        RperfNativeStats.attach(dir)
        val probeFile = File(dir, "rperf_native.json")
        assertTrue("attach 后应已写出探活文件", probeFile.isFile)
        assertEquals(0, JSONObject(probeFile.readText()).getInt("n"))

        // 非 canvasNodeFlush 的 publish 不产出样本。
        val other = RperfNativeStats.open()!!
        RperfNativeStats.markFlush(other, "render", JSONObject().put("name", "firstRender"))
        RperfNativeStats.addJson(other, 9_000_000L)
        RperfNativeStats.finish(other)
        flushNow()
        val empty = JSONObject(probeFile.readText())
        assertEquals("非 flush 帧不得入窗", 0, empty.getInt("n"))
        assertEquals(0L, empty.getLong("samples_total"))

        // 5 帧 flush：json/packet/post 注入固定值，handler 为真实耗时。
        repeat(5) { index ->
            val probe = RperfNativeStats.open()!!
            val body = JSONObject()
                .put("name", "canvasNodeFlush")
                .put("params", JSONObject().put("operationsCompact", "x".repeat(10)))
            RperfNativeStats.markFlush(probe, "render", body)
            RperfNativeStats.addJson(probe, (index + 1) * 100_000L) // 0.1..0.5 ms
            RperfNativeStats.addPacket(probe, 200_000L) // 0.2 ms
            RperfNativeStats.addPost(probe, 300_000L) // 0.3 ms
            RperfNativeStats.finish(probe)
            if (index == 0) {
                // handler 已返回才走到准入回调 → 记 1 次延迟准入（通道饱和信号）。
                RperfNativeStats.notifyAdmitted(probe)
            }
        }
        flushNow()

        val json = JSONObject(probeFile.readText())
        assertEquals(5, json.getInt("n"))
        assertEquals(5L, json.getLong("samples_total"))
        assertEquals(2, json.getInt("windows"))
        assertEquals(0, json.getInt("fallback_n"))
        assertEquals(1, json.getInt("late_admit_n"))

        // engine_qjs 反向依赖字段：本 JVM 无 engine 样本 → engine_n=0 且各列为 null（结构保持稳定）。
        assertEquals(0, json.getInt("engine_n"))
        assertTrue(json.isNull("engine_serialize_p50_ms"))
        assertTrue(json.isNull("engine_parse_p50_ms"))
        assertEquals(10, json.getInt("ops_chars_p50"))
        assertEquals(10, json.getInt("ops_chars_max"))

        // nearest-rank：idx = floor(p*n)，n=5 → p50=idx2、p90/max=idx4。
        assertEquals(0.3, json.getDouble("json_p50_ms"), 1e-9)
        assertEquals(0.5, json.getDouble("json_p90_ms"), 1e-9)
        assertEquals(0.5, json.getDouble("json_max_ms"), 1e-9)
        assertEquals(0.2, json.getDouble("packet_p50_ms"), 1e-9)
        assertEquals(0.3, json.getDouble("post_p50_ms"), 1e-9)

        // handler 取真实值：只断言非负且 p90 与 max 在单调关系上一致（n=5 时同一分位点）。
        assertTrue(json.getDouble("handler_p50_ms") >= 0.0)
        assertTrue(json.getDouble("handler_max_ms") >= json.getDouble("handler_p50_ms"))
        assertEquals(json.getDouble("handler_max_ms"), json.getDouble("handler_p90_ms"), 1e-9)

        // [rperf] game touch 快路段：3 条样本 + 折叠计数，非法值（负数/0）忽略。
        RperfNativeStats.recordTouch(150_000L, 250_000L)
        RperfNativeStats.recordTouch(350_000L, 450_000L)
        RperfNativeStats.recordTouch(550_000L, 650_000L)
        RperfNativeStats.addTouchCoalesced(7)
        RperfNativeStats.addTouchCoalesced(-1)
        RperfNativeStats.addTouchCoalesced(0)
        flushNow()
        val withTouch = JSONObject(probeFile.readText())
        assertEquals(3, withTouch.getInt("touch_n"))
        assertEquals(7, withTouch.getInt("touch_coalesced_n"))
        assertEquals("touch 段序列化 p50", 0.35, withTouch.getDouble("touch_serialize_p50_ms"), 1e-9)
        assertEquals("touch 段序列化 max", 0.55, withTouch.getDouble("touch_serialize_max_ms"), 1e-9)
        assertEquals("touch 段 post max", 0.65, withTouch.getDouble("touch_post_max_ms"), 1e-9)
        // 下一窗 touch 段清零。
        flushNow()
        assertEquals(0, JSONObject(probeFile.readText()).getInt("touch_n"))
        assertEquals(0L, JSONObject(probeFile.readText()).getLong("touch_coalesced_n"))

        // 原子写：rename 成功后不残留 .tmp。
        assertFalse("写盘应为 rename 而非残留 tmp", File(dir, "rperf_native.json.tmp").exists())
    }

    private fun flushNow() {
        val method = RperfNativeStats::class.java.getDeclaredMethod("flush")
        method.isAccessible = true
        method.invoke(RperfNativeStats)
    }
}
