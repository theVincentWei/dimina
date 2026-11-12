package com.didi.dimina.ui.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the WeChat-semantics exit/float wiring (2026-11-12):
 *
 * - capsule ✕ / root back / nav-bar root back / ⋯menu「关闭小程序」all route through
 *   [DiminaActivity.exitMiniProgram] (destroy runtime, cold start next open);
 * - ⋯menu gains a「浮窗」tile as the ONLY background route: it goes through
 *   [DiminaActivity.hideMiniProgram] which notifies the host via
 *   MiniApp.notifyFloated(appId, name, iconPng);
 * - MiniApp.clear (the runtime-destruction choke point) notifies
 *   onRuntimeDestroyed so the host can drop a stale float ball;
 * - foreground transitions (onStart / navigation resume paths) notify
 *   onForegrounded so the host can drop the float ball on restore;
 * - the host-facing listener is exposed on the Dimina facade.
 *
 * Assertions read the source text, same rationale as [DiminaActivityCapsuleWiringTest].
 */
class DiminaActivityFloatExitWiringTest {

    private val activitySource: String by lazy {
        val relative = "src/main/kotlin/com/didi/dimina/ui/container/DiminaActivity.kt"
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("DiminaActivity.kt not found from ${File(".").absolutePath}")
        found.readText()
    }

    private val miniAppSource: String by lazy {
        val relative = "src/main/kotlin/com/didi/dimina/core/MiniApp.kt"
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("MiniApp.kt not found from ${File(".").absolutePath}")
        found.readText()
    }

    private val diminaSource: String by lazy {
        val relative = "src/main/kotlin/com/didi/dimina/Dimina.kt"
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("Dimina.kt not found from ${File(".").absolutePath}")
        found.readText()
    }

    @Test
    fun `every exit affordance routes through exitMiniProgram - never hideMiniProgram`() {
        val rootBacks = Regex(Regex.escape("if (miniProgram.root) exitMiniProgram() else finish()"))
            .findAll(activitySource).count()
        assertEquals(
            "system back and nav-bar back at the root page must both exit (2 call sites)",
            2,
            rootBacks,
        )
        assertFalse(
            "the old hide-on-root-back semantic must not come back",
            activitySource.contains("if (miniProgram.root) hideMiniProgram()"),
        )
        assertTrue(
            "capsule ✕ must exit (destroy runtime), not hide",
            activitySource.contains("onCloseClick = { exitMiniProgram() }"),
        )
        assertTrue(
            "⋯menu「关闭小程序」must exit too - the menu close click handler calls exitMiniProgram",
            activitySource.contains("关闭小程序=真退出") &&
                activitySource.contains("收后台请走「浮窗」。\n                    exitMiniProgram()"),
        )
    }

    @Test
    fun `float is the only background route and notifies the host`() {
        assertTrue(
            "the ⋯menu sheet must gain a「浮窗」tile wired through onFloatClick",
            activitySource.contains("label = \"浮窗\"") && activitySource.contains("onFloatClick"),
        )
        assertTrue(
            "the float click must go through hideMiniProgram (the retain-then-notify primitive)",
            activitySource.contains("onFloatClick = {\n" +
                "                    showMiniProgramMenu.value = false\n" +
                "                    // 浮窗(2026-11-12):唯一收后台入口。整栈保留+运行时保留,\n" +
                "                    // 并经 notifyFloated 通知宿主弹浮窗球。\n" +
                "                    hideMiniProgram()\n" +
                "                },"),
        )
        val hiding = activitySource.substringAfter("fun hideMiniProgram()")
        assertTrue(
            "hideMiniProgram must notify the host with appId/name/iconPng after the task is parked",
            hiding.contains("notifyFloated(miniProgram.appId, miniProgram.name, miniProgram.iconPng)"),
        )
    }

    @Test
    fun `runtime destruction and foreground transitions notify the listener`() {
        assertTrue(
            "MiniApp.clear is the destruction choke point - it must fire onRuntimeDestroyed",
            miniAppSource.contains("notifyRuntimeDestroyed(appId)"),
        )
        val foregroundedCount = Regex(Regex.escape("MiniApp.getInstance().notifyForegrounded(miniProgram.appId)"))
            .findAll(activitySource).count()
        assertEquals(
            "foreground transitions (onStart + two navigation-resume paths) must fire onForegrounded",
            3,
            foregroundedCount,
        )
    }

    @Test
    fun `host-facing listener is exposed on the Dimina facade`() {
        assertTrue(
            "Dimina facade must expose setOnMiniProgramLifecycleListener",
            diminaSource.contains("fun setOnMiniProgramLifecycleListener(listener: MiniProgramLifecycleListener?)"),
        )
        assertTrue(
            "the listener interface must carry floated/foregrounded/destroyed",
            File(
                listOf(
                    "src/main/kotlin/com/didi/dimina/core/MiniProgramLifecycleListener.kt",
                    "dimina/src/main/kotlin/com/didi/dimina/core/MiniProgramLifecycleListener.kt",
                ).firstOrNull { File(it).isFile } ?: ""
            ).readText().contains("interface MiniProgramLifecycleListener"),
        )
    }
}
