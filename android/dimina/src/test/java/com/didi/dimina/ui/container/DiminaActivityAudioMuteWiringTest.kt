package com.didi.dimina.ui.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the capsule-side audio mute button + audio declaration + version subtitle +
 * launch-loading theme wiring inside [DiminaActivity] / [AudioApi] / [AppConfig] (2026-11-12):
 *
 * - the mute button renders ONLY inside the expanded-capsule branch (collapses with the capsule,
 *   per the dsh-go capsule contract);
 * - visibility has two signals: app.json "requiredBackgroundModes" containing "audio"
 *   (declared -> visible as soon as the config lands) OR the first AudioApi instance creation
 *   (runtime fallback for legacy packages that never declared);
 * - mute semantics = AudioApi global effective volume 0 (playback state/events untouched),
 *   applied to live instances immediately and to instances created later;
 * - app session teardown (clearApp) resets both the audio-touched and mute state;
 * - the menu sheet subtitle shows "v<versionName>" (versionCode fallback, 小程序 last);
 * - the launch loading mask follows the system theme and paints the package icon as a circle
 *   (letter tile only as fallback).
 *
 * Assertions read the source text, same rationale as [DiminaActivityCapsuleWiringTest].
 */
class DiminaActivityAudioMuteWiringTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
    }

    private val activitySource: String by lazy {
        readSource("src/main/kotlin/com/didi/dimina/ui/container/DiminaActivity.kt")
    }

    private val audioApiSource: String by lazy {
        readSource("src/main/kotlin/com/didi/dimina/api/media/AudioApi.kt")
    }

    private val appConfigSource: String by lazy {
        readSource("src/main/kotlin/com/didi/dimina/bean/AppConfig.kt")
    }

    @Test
    fun `mute button renders only inside the expanded capsule branch`() {
        val segment = activitySource
            .substringAfter("if (capsuleCollapsed.value) {")
            .substringBefore("MiniProgramCapsuleButton(")
        val collapsedBranch = segment.substringBefore("} else {")
        val expandedBranch = segment.substringAfter("} else {")
        assertTrue(
            "the mute button must sit in the expanded branch, gated by miniProgramAudioActive",
            expandedBranch.contains("if (miniProgramAudioActive.value)"),
        )
        assertFalse(
            "the collapsed (hairline handle) branch must not render the mute button",
            collapsedBranch.contains("MiniProgramMuteButton"),
        )
    }

    @Test
    fun `declaration gate lights the button as soon as the config parses`() {
        assertTrue(
            "AppConfig must expose the WeChat-style audio declaration",
            appConfigSource.contains("val requiredBackgroundModes: List<String>? = null"),
        )
        assertTrue(
            activitySource.contains("requiredBackgroundModes?.contains(\"audio\") == true"),
        )
    }

    @Test
    fun `runtime fallback lights the button on the first audio instance`() {
        assertTrue(audioApiSource.contains("if (audioApps.add(appId)) {"))
        assertTrue(audioApiSource.contains("activity.markMiniProgramAudioActive()"))
    }

    @Test
    fun `mute is effective-volume based and reaches live plus future instances`() {
        assertTrue(audioApiSource.contains("fun setMuted(appId: String, muted: Boolean) {"))
        assertTrue(
            "setProperty must route through the effective volume (mute-aware)",
            audioApiSource.contains("applyEffectiveVolume(target)"),
        )
        assertTrue(
            "onPrepared must re-apply the effective volume so autoplay/pending start is muted too",
            audioApiSource.contains("// 静音位在 prepare 完成时落一次"),
        )
    }

    @Test
    fun `app session teardown resets the audio-touched and mute state`() {
        assertTrue(
            audioApiSource.contains("audioApps.remove(appId)") &&
                audioApiSource.contains("mutedApps.remove(appId)"),
        )
    }

    @Test
    fun `menu sheet subtitle shows the version line`() {
        assertTrue(
            activitySource.contains("\"v\" + versionName.trim().removePrefix(\"v\")"),
        )
        assertTrue(
            activitySource.contains("versionCode > 0 -> \"v\$versionCode\""),
        )
    }

    @Test
    fun `launch loading mask follows the system theme and paints the package icon as a circle`() {
        val loading = activitySource
            .substringAfter("fun LoadingAnimation(miniProgram: MiniProgram)")
            .substringBefore("更新当前页面的路径并重新加载")
        assertTrue(loading.contains("capsuleDarkOverride() ?: isSystemInDarkTheme()"))
        assertTrue(loading.contains("BitmapFactory.decodeByteArray"))
        assertTrue(loading.contains(".clip(CircleShape)"))
        assertTrue(
            "the letter tile is only the fallback (real package icon first)",
            loading.contains("if (appIcon != null)"),
        )
    }
}
