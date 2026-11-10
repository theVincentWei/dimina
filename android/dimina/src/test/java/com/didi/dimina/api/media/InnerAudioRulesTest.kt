package com.didi.dimina.api.media

import android.media.MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** InnerAudioRules 纯函数：errCode 映射、事件归一、参数收敛（契约 v1）。 */
class InnerAudioRulesTest {

    // ---- errCode 映射：10001 系统 / 10002 网络 / 10003 文件 / 10004 格式 / 10005 解码 ----

    @Test fun unsupportedFormatMapsTo10004() {
        assertEquals(10004, InnerAudioRules.mapPlayerError(
            MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_UNSUPPORTED))
    }

    @Test fun malformedStreamMapsTo10005() {
        assertEquals(10005, InnerAudioRules.mapPlayerError(
            MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_MALFORMED))
    }

    @Test fun ioAndTimeoutMapToNetwork10002() {
        assertEquals(10002, InnerAudioRules.mapPlayerError(
            MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO))
        assertEquals(10002, InnerAudioRules.mapPlayerError(
            MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_TIMED_OUT))
    }

    @Test fun serverDiedMapsToSystem10001() {
        assertEquals(10001, InnerAudioRules.mapPlayerError(
            MediaPlayer.MEDIA_ERROR_SERVER_DIED, 0))
    }

    @Test fun unknownErrorsFallBackToSystem10001() {
        assertEquals(10001, InnerAudioRules.mapPlayerError(MediaPlayer.MEDIA_ERROR_UNKNOWN, -2147483648))
        assertEquals(10001, InnerAudioRules.mapPlayerError(12345, 6789))
    }

    @Test fun dataSourcePhaseFailuresMapToMissingFile10003() {
        assertEquals(10003, InnerAudioRules.mapDataSourceFailure(IOException("file not found")))
        assertEquals(10003, InnerAudioRules.mapDataSourceFailure(IllegalArgumentException("bad path")))
        assertEquals(10003, InnerAudioRules.mapDataSourceFailure(IllegalStateException("state")))
        assertEquals(10001, InnerAudioRules.mapDataSourceFailure(RuntimeException("unexpected")))
    }

    // ---- 事件归一：契约钉死裸驼峰名，on 前缀容错 ----

    @Test fun contractCanonicalEventNamesPassThrough() {
        InnerAudioRules.CANONICAL_EVENTS.forEach { event ->
            assertEquals(event, InnerAudioRules.normalizeEvent(event))
        }
    }

    @Test fun onPrefixedEventNamesAreTolerated() {
        assertEquals("timeUpdate", InnerAudioRules.normalizeEvent("onTimeUpdate"))
        assertEquals("canplay", InnerAudioRules.normalizeEvent("onCanplay"))
        assertEquals("loadedmetadata", InnerAudioRules.normalizeEvent("OnLoadedmetadata"))
    }

    @Test fun unknownEventsAreRejected() {
        assertNull(InnerAudioRules.normalizeEvent("onFrameRecorded"))
        assertNull(InnerAudioRules.normalizeEvent("loading"))
        assertNull(InnerAudioRules.normalizeEvent(""))
        assertNull(InnerAudioRules.normalizeEvent("on"))
    }

    // ---- 参数收敛 ----

    @Test fun volumeIsClampedIntoZeroToOne() {
        assertEquals(0.0, InnerAudioRules.clampVolume(-0.5), 0.0)
        assertEquals(1.0, InnerAudioRules.clampVolume(1.7), 0.0)
        assertEquals(0.5, InnerAudioRules.clampVolume(0.5), 0.0)
        assertEquals(1.0, InnerAudioRules.clampVolume(Double.NaN), 0.0)
    }

    @Test fun playbackRateIsClampedIntoHalfToTwo() {
        assertEquals(0.5, InnerAudioRules.clampPlaybackRate(0.2), 0.0)
        assertEquals(2.0, InnerAudioRules.clampPlaybackRate(5.0), 0.0)
        assertEquals(1.5, InnerAudioRules.clampPlaybackRate(1.5), 0.0)
        assertEquals(1.0, InnerAudioRules.clampPlaybackRate(Double.NaN), 0.0)
        assertEquals(1.0, InnerAudioRules.clampPlaybackRate(Double.POSITIVE_INFINITY), 0.0)
    }
}
