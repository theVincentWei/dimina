package com.didi.dimina.api.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** RecorderRules 纯函数：参数校验、状态机、事件映射、计时器、WAV 头（契约 v1）。 */
class RecorderRulesTest {

    // ---- start 参数校验：format 仅 aac|wav，其余 fail ----

    @Test fun aacAndWavAreTheOnlyAcceptedFormats() {
        assertNull(RecorderRules.validateStart("aac", 8000, 1, 48000))
        assertNull(RecorderRules.validateStart("wav", 44100, 2, 48000))
        assertEquals("invalid format", RecorderRules.validateStart("mp3", 8000, 1, 48000))
        assertEquals("invalid format", RecorderRules.validateStart("PCM", 8000, 1, 48000))
        assertEquals("invalid format", RecorderRules.validateStart("", 8000, 1, 48000))
    }

    @Test fun sampleRateMustBeAWeChatSupportedRate() {
        assertEquals("invalid sampleRate", RecorderRules.validateStart("aac", 8001, 1, 48000))
        assertEquals("invalid sampleRate", RecorderRules.validateStart("aac", 44101, 1, 48000))
        RecorderRules.VALID_SAMPLE_RATES.forEach { rate ->
            assertNull(RecorderRules.validateStart("aac", rate, 1, 48000))
        }
    }

    @Test fun channelCountIsRestrictedToOneOrTwo() {
        assertNull(RecorderRules.validateStart("aac", 16000, 2, 48000))
        assertEquals("invalid numberOfChannels", RecorderRules.validateStart("aac", 16000, 0, 48000))
        assertEquals("invalid numberOfChannels", RecorderRules.validateStart("aac", 16000, 3, 48000))
    }

    @Test fun encodeBitRateHasBounds() {
        assertNull(RecorderRules.validateStart("aac", 16000, 1, 16000))
        assertNull(RecorderRules.validateStart("aac", 16000, 1, 320000))
        assertEquals("invalid encodeBitRate", RecorderRules.validateStart("aac", 16000, 1, 15999))
        assertEquals("invalid encodeBitRate", RecorderRules.validateStart("aac", 16000, 1, 320001))
    }

    // ---- duration：默认 60000，上限 600000 ----

    @Test fun durationIsDefaultedAndCapped() {
        assertEquals(60000L, RecorderRules.clampDurationMs(0L))
        assertEquals(60000L, RecorderRules.clampDurationMs(-5L))
        assertEquals(30000L, RecorderRules.clampDurationMs(30000L))
        assertEquals(600000L, RecorderRules.clampDurationMs(600000L))
        assertEquals(600000L, RecorderRules.clampDurationMs(700000L))
    }

    // ---- 事件 wire 名映射 ----

    @Test fun onAndOffWireNamesMapToCanonicalEvents() {
        assertEquals("start", RecorderRules.eventForWire("onStart"))
        assertEquals("pause", RecorderRules.eventForWire("onPause"))
        assertEquals("stop", RecorderRules.eventForWire("onStop"))
        assertEquals("error", RecorderRules.eventForWire("onError"))
        assertEquals("interruptionBegin", RecorderRules.eventForWire("onInterruptionBegin"))
        assertEquals("interruptionEnd", RecorderRules.eventForWire("onInterruptionEnd"))
        assertEquals("start", RecorderRules.eventForWire("offStart"))
        assertEquals("interruptionBegin", RecorderRules.eventForWire("offInterruptionBegin"))
        assertEquals("interruptionEnd", RecorderRules.eventForWire("offInterruptionEnd"))
    }

    @Test fun frameRecordedIsNeverSupported() {
        assertNull(RecorderRules.eventForWire("onFrameRecorded"))
        assertNull(RecorderRules.eventForWire("offFrameRecorded"))
        assertNull(RecorderRules.eventForWire("onResume"))
        assertNull(RecorderRules.eventForWire("start"))
    }

    // ---- 状态机：pause/resume/stop 的合法迁移 ----

    @Test fun pauseRequiresActiveRecording() {
        assertNull(RecorderRules.validateOp(RecorderState.RECORDING, "pause"))
        assertNotNull(RecorderRules.validateOp(RecorderState.IDLE, "pause"))
        assertNotNull(RecorderRules.validateOp(RecorderState.PAUSED, "pause"))
    }

    @Test fun resumeRequiresPausedRecording() {
        assertNull(RecorderRules.validateOp(RecorderState.PAUSED, "resume"))
        assertNotNull(RecorderRules.validateOp(RecorderState.RECORDING, "resume"))
        assertNotNull(RecorderRules.validateOp(RecorderState.IDLE, "resume"))
    }

    @Test fun stopWorksFromRecordingOrPausedOnly() {
        assertNull(RecorderRules.validateOp(RecorderState.RECORDING, "stop"))
        assertNull(RecorderRules.validateOp(RecorderState.PAUSED, "stop"))
        assertNotNull(RecorderRules.validateOp(RecorderState.IDLE, "stop"))
    }
}

/** ElapsedTimer：pause 期间不计时（aac 时长口径）。 */
class ElapsedTimerTest {
    private class FakeClock {
        var nowMs = 1000L
        fun tick(delta: Long) {
            nowMs += delta
        }
    }

    @Test fun pausedSpansDoNotAccumulate() {
        val clock = FakeClock()
        val timer = ElapsedTimer { clock.nowMs }
        timer.start()
        clock.tick(1000)
        timer.pause() // 累计 1000ms
        clock.tick(5000) // 暂停期间：不计
        assertEquals(1000L, timer.elapsedMs())
        timer.resume()
        clock.tick(500)
        assertEquals(1500L, timer.elapsedMs())
        assertEquals(1500L, timer.stop())
        clock.tick(100)
        assertEquals(1500L, timer.elapsedMs())
    }

    @Test fun elapsedWhileRunningIncludesOpenSpan() {
        val clock = FakeClock()
        val timer = ElapsedTimer { clock.nowMs }
        timer.start()
        clock.tick(250)
        assertEquals(250L, timer.elapsedMs())
        clock.tick(250)
        assertEquals(500L, timer.elapsedMs())
    }
}

/** WAV 头字节布局（wav 引擎产物契约：44 字节 PCM16 RIFF/WAVE）。 */
class WavHeaderTest {
    @Test fun headerMatchesCanonicalWavLayout() {
        val sampleRate = 16000
        val channels = 1
        val dataSize = 32000
        val header = buildWavHeader(sampleRate, channels, dataSize)
        assertEquals(44, header.size)
        assertArrayEquals("RIFF".toByteArray(), header.copyOfRange(0, 4))
        assertEquals(36 + dataSize, readIntLe(header, 4))
        assertArrayEquals("WAVE".toByteArray(), header.copyOfRange(8, 12))
        assertArrayEquals("fmt ".toByteArray(), header.copyOfRange(12, 16))
        assertEquals(16, readIntLe(header, 16)) // fmt chunk size
        assertEquals(1, readShortLe(header, 20)) // PCM
        assertEquals(channels, readShortLe(header, 22))
        assertEquals(sampleRate, readIntLe(header, 24))
        assertEquals(sampleRate * channels * 2, readIntLe(header, 28)) // byte rate
        assertEquals(channels * 2, readShortLe(header, 32)) // block align
        assertEquals(16, readShortLe(header, 34)) // bits per sample
        assertArrayEquals("data".toByteArray(), header.copyOfRange(36, 40))
        assertEquals(dataSize, readIntLe(header, 40))
    }

    @Test fun stereoHeaderScalesByteRateAndBlockAlign() {
        val header = buildWavHeader(48000, 2, 96000)
        assertEquals(48000 * 2 * 2, readIntLe(header, 28))
        assertEquals(4, readShortLe(header, 32))
        assertEquals(2, readShortLe(header, 22))
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24))

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
