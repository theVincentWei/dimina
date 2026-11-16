package com.didi.dimina.engine.qjs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [rperf] engine_qjs 侧时序统计器自检（微维测期，可随 RperfEngineStats 一起删除）。
 *
 * 断言：一窗样本按列升序返回、drain 幂等清空、负值/异常不污染、jsonChars 原样保留。
 */
class RperfEngineStatsTest {

    @Test
    fun `drain returns sorted window and clears it`() {
        // 先确保上一个用例/别的线程没有残留。
        RperfEngineStats.drain()

        RperfEngineStats.record(3_000_000L, 2_000_000L, 1_000_000L, 70_000)
        RperfEngineStats.record(1_000_000L, 1_500_000L, 2_000_000L, 100)
        RperfEngineStats.record(2_000_000L, 1_000_000L, 3_000_000L, 50)

        val snapshot = RperfEngineStats.drain()
        assertEquals(3, snapshot.n)
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), snapshot.serializeNs)
        assertEquals(listOf(1_000_000L, 1_500_000L, 2_000_000L), snapshot.jniNs)
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), snapshot.parseNs)
        assertEquals(listOf(50, 100, 70_000), snapshot.jsonChars)
        assertEquals(0, snapshot.dropped)

        // drain 取走并清空，下一次为空样本。
        val empty = RperfEngineStats.drain()
        assertEquals(0, empty.n)
        assertEquals(emptyList<Long>(), empty.serializeNs)
    }

    @Test
    fun `negative timings are clamped to zero`() {
        RperfEngineStats.drain()
        RperfEngineStats.record(-5L, -1L, -100L, 10)
        val snapshot = RperfEngineStats.drain()
        assertEquals(1, snapshot.n)
        assertEquals(0L, snapshot.serializeNs[0])
        assertEquals(0L, snapshot.jniNs[0])
        assertEquals(0L, snapshot.parseNs[0])
        assertEquals(10, snapshot.jsonChars[0])
    }
}
