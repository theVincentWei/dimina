package com.didi.dimina.ui.view

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [game-touch] 游戏触摸快路纯逻辑自检（微维测/AB 期，可随实现一起删除）：
 * 按帧合帧器（latest-wins ≤1 条/帧、DOWN/UP/CANCEL 保序 flush、调度标记不卡死）、
 * JSON 序列化逐字段契约（对齐 fe runtime.js sendGameTouch）、开关文件读取。
 * MotionEvent 适配层依赖 android.jar，不在 JVM 单测覆盖范围。
 */
class GameTouchNativeRelayTest {

    // ---- 合帧器 ----

    private class FakeScheduler : ((Runnable) -> Unit) {
        val frames = ArrayList<Runnable>()
        override fun invoke(p1: Runnable) {
            frames.add(p1)
        }

        fun runAll() {
            val pending = ArrayList(frames)
            frames.clear()
            pending.forEach { it.run() }
        }
    }

    private class Recorder : (GameTouchSnapshot, Int) -> Unit {
        val sent = ArrayList<Pair<GameTouchSnapshot, Int>>()
        override fun invoke(p1: GameTouchSnapshot, p2: Int) {
            sent.add(p1 to p2)
        }
    }

    private fun snapshot(type: String, marker: Int = 0) = GameTouchSnapshot(
        eventType = type,
        touches = listOf(GameTouchPoint(marker, marker * 1.0, marker * 2.0, 1.0)),
        changedTouches = listOf(GameTouchPoint(marker, marker * 1.0, marker * 2.0, 1.0)),
        timeStampMs = marker * 1.5,
        wallMs = 1000L + marker,
    )

    @Test
    fun `moves within one frame coalesce into latest single message`() {
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val batcher = GameTouchFrameBatcher(scheduler, recorder)

        batcher.offer(snapshot("touchmove", 1))
        batcher.offer(snapshot("touchmove", 2))
        batcher.offer(snapshot("touchmove", 3))
        assertTrue("帧回调前不得提前发出", recorder.sent.isEmpty())
        assertEquals("同帧只排一次帧回调", 1, scheduler.frames.size)

        scheduler.runAll()
        assertEquals("一帧最多一条", 1, recorder.sent.size)
        assertEquals("touchmove", recorder.sent[0].first.eventType)
        assertEquals("latest-wins：发出的是最新快照", 3, recorder.sent[0].first.touches[0].identifier)
        assertEquals("折叠条数=本帧 move 总数", 3, recorder.sent[0].second)
    }

    @Test
    fun `down up cancel flush pending move first then send immediately`() {
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val batcher = GameTouchFrameBatcher(scheduler, recorder)

        batcher.offer(snapshot("touchmove", 1))
        batcher.offer(snapshot("touchstart", 2))
        assertEquals(
            "DOWN 先 flush 挂起 move 再立即发",
            listOf("touchmove", "touchstart"),
            recorder.sent.map { it.first.eventType },
        )
        assertEquals(1, recorder.sent[0].second)
        assertEquals(1, recorder.sent[1].second)

        batcher.offer(snapshot("touchmove", 3))
        batcher.offer(snapshot("touchend", 4))
        batcher.offer(snapshot("touchmove", 5))
        batcher.offer(snapshot("touchcancel", 6))
        assertEquals(
            listOf("touchmove", "touchstart", "touchmove", "touchend", "touchmove", "touchcancel"),
            recorder.sent.map { it.first.eventType },
        )
        // 帧回调空跑不得重复发送
        scheduler.runAll()
        assertEquals(6, recorder.sent.size)
    }

    @Test
    fun `scheduler flag never sticks after immediate flush`() {
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val batcher = GameTouchFrameBatcher(scheduler, recorder)

        // move 排了帧回调，随后 DOWN 立即 flush 掉 → 已排回调空跑一次并重置标记
        batcher.offer(snapshot("touchmove", 1))
        batcher.offer(snapshot("touchstart", 2))
        scheduler.runAll()

        // 标记已重置：后续 move 必须能重新调度并被新帧回调发出
        batcher.offer(snapshot("touchmove", 3))
        assertTrue("标记卡死会导致 move 永不调度", scheduler.frames.isNotEmpty())
        scheduler.runAll()
        assertEquals(
            listOf("touchmove", "touchstart", "touchmove"),
            recorder.sent.map { it.first.eventType },
        )
        assertEquals(3, recorder.sent[2].first.touches[0].identifier)
    }

    @Test
    fun `consecutive frames each emit one message`() {
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val batcher = GameTouchFrameBatcher(scheduler, recorder)

        batcher.offer(snapshot("touchmove", 1))
        scheduler.runAll()
        batcher.offer(snapshot("touchmove", 2))
        scheduler.runAll()
        assertEquals(2, recorder.sent.size)
        assertEquals(2, recorder.sent[1].first.touches[0].identifier)
    }

    @Test
    fun `reset drops pending move silently`() {
        val scheduler = FakeScheduler()
        val recorder = Recorder()
        val batcher = GameTouchFrameBatcher(scheduler, recorder)

        batcher.offer(snapshot("touchmove", 1))
        batcher.reset()
        scheduler.runAll()
        assertTrue(recorder.sent.isEmpty())
    }

    // ---- 序列化契约（对齐 fe runtime.js sendGameTouch）----

    /** JVM 单测 android stub 缺 keySet()，用 keys() 迭代器兜底。 */
    private fun keySetOf(json: JSONObject): Set<String> {
        val result = HashSet<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) result.add(iterator.next())
        return result
    }

    @Test
    fun `envelope matches fe sendGameTouch contract field by field`() {
        val snap = GameTouchSnapshot(
            eventType = "touchmove",
            touches = listOf(
                GameTouchPoint(identifier = 0, clientX = 12.5, clientY = 7.25, force = 1.0),
                GameTouchPoint(identifier = 1, clientX = 88.0, clientY = 40.0, force = 0.5),
            ),
            changedTouches = listOf(GameTouchPoint(identifier = 1, clientX = 88.0, clientY = 40.0, force = 0.5)),
            timeStampMs = 123.5,
            wallMs = 1699999999999L,
        )
        val envelope = GameTouchCodec.envelopeJson(snap, bridgeId = "bridge_abc")

        assertEquals("gameTouch", envelope.getString("type"))
        assertEquals("service", envelope.getString("target"))
        assertEquals("信封只应有 type/target/body 三键", setOf("type", "target", "body"), keySetOf(envelope))

        val body = envelope.getJSONObject("body")
        assertEquals(
            "body 六键：bridgeId/eventType/touches/changedTouches/timeStamp/__natWall",
            setOf("bridgeId", "eventType", "touches", "changedTouches", "timeStamp", "__natWall"),
            keySetOf(body),
        )
        assertEquals("bridge_abc", body.getString("bridgeId"))
        assertEquals("touchmove", body.getString("eventType"))
        assertEquals(123.5, body.getDouble("timeStamp"), 1e-9)
        assertEquals(1699999999999L, body.getLong("__natWall"))

        val touches = body.getJSONArray("touches")
        assertEquals(2, touches.length())
        val point = touches.getJSONObject(0)
        assertEquals(
            "触摸点六字段与 fe serializeTouch 一致",
            setOf("identifier", "clientX", "clientY", "pageX", "pageY", "force"),
            keySetOf(point),
        )
        assertEquals(0, point.getInt("identifier"))
        assertEquals(12.5, point.getDouble("clientX"), 1e-9)
        assertEquals(7.25, point.getDouble("clientY"), 1e-9)
        assertEquals("全屏 fixed canvas：pageX=clientX", point.getDouble("clientX"), point.getDouble("pageX"), 1e-9)
        assertEquals("pageY=clientY", point.getDouble("clientY"), point.getDouble("pageY"), 1e-9)
        assertEquals(1.0, point.getDouble("force"), 1e-9)

        val changed = body.getJSONArray("changedTouches")
        assertEquals(1, changed.length())
        assertEquals(88.0, changed.getJSONObject(0).getDouble("clientX"), 1e-9)
    }

    // ---- 开关 ----

    private fun switchDir(): File = File("build/game-touch-switch-test").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun writeSwitch(appId: String, content: String?): File {
        val dir = switchDir()
        val file = File(dir, "dimina-file-system/$appId/usr/${GameTouchRelaySwitch.FILE_NAME}")
        file.parentFile?.mkdirs()
        if (content != null) file.writeText(content)
        return dir
    }

    @Test
    fun `switch file missing defaults to enabled`() {
        assertTrue(GameTouchRelaySwitch.isEnabled(switchDir(), "wx123"))
    }

    @Test
    fun `switch content zero disables with or without trailing newline`() {
        assertFalse(GameTouchRelaySwitch.isEnabled(writeSwitch("wx123", "0"), "wx123"))
        assertFalse(GameTouchRelaySwitch.isEnabled(writeSwitch("wx123", "0\n"), "wx123"))
    }

    @Test
    fun `switch other values keep enabled`() {
        assertTrue(GameTouchRelaySwitch.isEnabled(writeSwitch("wx123", "1"), "wx123"))
        assertTrue(GameTouchRelaySwitch.isEnabled(writeSwitch("wx123", "off"), "wx123"))
    }

    @Test
    fun `runtime gate applies to game only`() {
        val dir = writeSwitch("wx123", "1")
        assertTrue(GameTouchRelaySwitch.isEnabledFor("game", dir, "wx123"))
        assertFalse("非 game 运行时不得接管", GameTouchRelaySwitch.isEnabledFor("miniProgram", dir, "wx123"))
        val offDir = writeSwitch("wx123", "0")
        assertFalse("game + 开关 '0' = A/B 基线臂", GameTouchRelaySwitch.isEnabledFor("game", offDir, "wx123"))
    }
}
