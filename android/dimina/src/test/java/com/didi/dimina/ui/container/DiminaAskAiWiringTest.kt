package com.didi.dimina.ui.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the「问AI浮窗」wiring (2026-11-15 spec):
 *
 * - the float window mounts on the DiminaActivity content layer gated by the single
 *   persisted state `Dimina.askAiEntryState().visible`;
 * - both close affordances (long-press ✕ inside the float, ⋯menu「问AI浮窗」tile)
 *   write the same persisted switch (SharedPreferences key `dimina_ask_ai_float_enabled`,
 *   default on); the menu tile sits FIRST in the tile row and greys out when closed;
 * - a float click forwards the host onClick AND emits the askAi event on the
 *   notifyFloated lifecycle chain with payload (appId, appName, pageTitle, pagePath);
 * - `Dimina.floatCurrentMiniProgram()` parks the current foreground mini program via
 *   the same hideMiniProgram primitive the「浮窗」menu tile uses.
 *
 * Assertions read the source text, same rationale as [DiminaActivityFloatExitWiringTest].
 */
class DiminaAskAiWiringTest {

    private fun source(relative: String): String {
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        return candidates.firstOrNull(File::isFile)
            ?.readText()
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
    }

    private val activitySource: String by lazy {
        source("src/main/kotlin/com/didi/dimina/ui/container/DiminaActivity.kt")
    }

    private val diminaSource: String by lazy {
        source("src/main/kotlin/com/didi/dimina/Dimina.kt")
    }

    private val buttonSource: String by lazy {
        source("src/main/kotlin/com/didi/dimina/ui/container/AskAiFloatButton.kt")
    }

    private val storeSource: String by lazy {
        source("src/main/kotlin/com/didi/dimina/common/AskAiFloatStore.kt")
    }

    @Test
    fun `float window mounts on the content layer gated by the persisted state`() {
        assertTrue(
            "DiminaContent must read the askAi entry state before any usage",
            activitySource.contains(
                "val askAiEntryState = com.didi.dimina.Dimina.getInstance().askAiEntryState()",
            ),
        )
        assertTrue(
            "the float window must render only while askAiEntryState.visible",
            activitySource.contains("if (askAiEntryState.visible) {\n                AskAiFloatButton("),
        )
        assertTrue(
            "the ✕ affordance must persist enabled=false, not just unmount",
            activitySource.contains(
                "onClose = { com.didi.dimina.Dimina.getInstance().setAskAiFloatEnabled(false) }",
            ),
        )
    }

    @Test
    fun `menu sheet toggles the same persisted state and the tile sits first`() {
        assertTrue(
            "the sheet call site must pass the live visibility and a toggle handler",
            activitySource.contains("askAiFloatVisible = askAiEntryState.visible") &&
                activitySource.contains("setAskAiFloatEnabled(!askAiEntryState.visible)"),
        )
        val sheet = activitySource.substringAfter("private fun MiniProgramMenuSheet(")
        val tileRow = sheet.substringAfter("horizontalArrangement = Arrangement.spacedBy(16.dp)")
        val askAiTile = tileRow.indexOf("MiniProgramAskAiMenuTile(")
        val floatTile = tileRow.indexOf("label = \"浮窗\"")
        assertTrue("the「问AI浮窗」tile must exist in the menu sheet row", askAiTile >= 0)
        assertTrue(
            "the「问AI浮窗」tile must be the FIRST tile, ahead of「浮窗」",
            askAiTile < floatTile,
        )
    }

    @Test
    fun `float click forwards host callback and emits the askAi event payload`() {
        val handler = activitySource.substringAfter("private fun onAskAiFloatClicked()")
        assertTrue(
            "page context must be normalized (blank → null), never fabricated",
            handler.contains(
                "AskAiEventPayload.normalizePageContext(title, currentPagePath.value)",
            ),
        )
        assertTrue(
            "appName falls back to page title then 小程序, same as the ⋯menu header",
            handler.contains("miniProgram.name.ifBlank { title.ifBlank { \"小程序\" } }"),
        )
        assertTrue(
            "the host onClick must be forwarded",
            handler.contains("askAiEntryState().onClick?.invoke()"),
        )
        assertTrue(
            "the event must carry exactly (appId, appName, pageTitle, pagePath)",
            handler.contains(
                "notifyAskAiInvoked(miniProgram.appId, appName, pageTitle, pagePath)",
            ),
        )
    }

    @Test
    fun `floatCurrentMiniProgram routes through the hideMiniProgram primitive`() {
        assertTrue(
            "Dimina facade must expose floatCurrentMiniProgram(): Boolean",
            diminaSource.contains("fun floatCurrentMiniProgram(): Boolean"),
        )
        assertTrue(
            "the facade must delegate to DiminaActivity.floatCurrentMiniProgramFromHost()",
            diminaSource.contains(
                "fun floatCurrentMiniProgram(): Boolean = DiminaActivity.floatCurrentMiniProgramFromHost()",
            ),
        )
        val hostHelper = activitySource.substringAfter("internal fun floatCurrentMiniProgramFromHost()")
        assertTrue(
            "the host helper must park the foreground app via activity.hideMiniProgram()",
            hostHelper.contains("activity.hideMiniProgram()"),
        )
        assertTrue(
            "the host helper must locate the foreground app from the visibility tracker",
            activitySource.contains("visibilityTracker.foregroundAppIds().lastOrNull()"),
        )
    }

    @Test
    fun `facade persists the switch and keeps the observable state in sync`() {
        assertTrue(
            "Dimina must read the persisted switch at init (default on before any write)",
            diminaSource.contains("AskAiFloatStore.readEnabled(appContext)"),
        )
        assertTrue(
            "setAskAiFloatEnabled must write the store and sync the observable visible",
            diminaSource.contains(
                "com.didi.dimina.common.AskAiFloatStore.writeEnabled(appContext, enabled)",
            ) && diminaSource.contains("askAiEntryStateImpl.visible = enabled"),
        )
        assertTrue(
            "hosts need a public click hook (set before startMiniProgram)",
            diminaSource.contains("fun setOnAskAiEntryClick(callback: (() -> Unit)?)"),
        )
    }

    @Test
    fun `persisted switch uses the spec'd global key with default on`() {
        assertTrue(
            "the store must pin the spec key literal",
            storeSource.contains("const val KEY_ENABLED = \"dimina_ask_ai_float_enabled\""),
        )
        assertTrue(
            "the store must default to enabled",
            storeSource.contains("const val DEFAULT_ENABLED = true"),
        )
    }

    @Test
    fun `float button matches the frozen visual spec`() {
        assertTrue(
            "44×44dp hit area, circular twin-star badge filling it (42dp emblem, ~1dp border inset, 2026-11-15 user finalized)",
            buttonSource.contains("ASK_AI_FLOAT_SIZE_DP = 44") &&
                buttonSource.contains("ASK_AI_FLOAT_ICON_DP = 42"),
        )
        assertTrue(
            "no glass box: blur/base-color machinery removed (2026-11-15 second pass)",
            !buttonSource.contains("Color(0x8C0F141E)") &&
                !buttonSource.contains("Color(0xF00F141E)") &&
                !buttonSource.contains("ASK_AI_FLOAT_BLUR_DP"),
        )
        assertTrue(
            "emblem ring: 50% white border + 10% inner ring on the #071328 disc",
            buttonSource.contains("ASK_AI_EMBLEM_BORDER = Color.White.copy(alpha = 0.50f)") &&
                buttonSource.contains("ASK_AI_EMBLEM_INNER_RING = Color.White.copy(alpha = 0.10f)") &&
                buttonSource.contains("Color(0xFF071328)"),
        )
        assertTrue(
            "twin star path literals from the spec, enlarged 25% (big #F5C24B / small #FFE08A)",
            buttonSource.contains("quadraticBezierTo(47.5f, 45.5f, 71f, 50f)") &&
                buttonSource.contains("quadraticBezierTo(69.9f, 34.3f, 82f, 37f)") &&
                buttonSource.contains("Color(0xFFF5C24B)") &&
                buttonSource.contains("Color(0xFFFFE08A)"),
        )
        assertTrue(
            "default position: right edge at 62% of the movable area",
            buttonSource.contains("ASK_AI_DEFAULT_Y_FRACTION = 0.62f"),
        )
        assertTrue(
            "edit mode: scale 1.1 + ±3° infinite wobble + 20dp #E5484D ✕ badge",
            buttonSource.contains("ASK_AI_EDIT_SCALE = 1.1f") &&
                buttonSource.contains("ASK_AI_EDIT_WOBBLE_DEGREES = 3f") &&
                buttonSource.contains("ASK_AI_BADGE_SIZE_DP = 20") &&
                buttonSource.contains("Color(0xFFE5484D)"),
        )
        assertTrue(
            "editing renders a scrim so taps outside cancel the edit mode",
            buttonSource.contains("if (editing) {") && buttonSource.contains(") { editing = false }"),
        )
        assertTrue(
            "menu tile miniature greys out (alpha 0.45 + #8A93A6 stars) when the float is closed",
            buttonSource.contains("ASK_AI_EMBLEM_DIM_STAR = Color(0xFF8A93A6)") &&
                buttonSource.contains("if (dimmed) 0.45f else 1f"),
        )
    }

    @Test
    fun `floatCurrentMiniProgramFromHost is callable with no foreground mini program`() {
        // 可调性冒烟:空账本(无前台小程序)必须优雅返回 false,不抛不崩。
        // 真实成功路径需要真 Activity,由 DiminaActivityFloatExitWiringTest 的源码 pin 兜住。
        assertFalse(DiminaActivity.floatCurrentMiniProgramFromHost())
    }

    @Test
    fun `visibility tracker exposes foreground app ids for the host helper`() {
        val tracker = source("src/main/kotlin/com/didi/dimina/ui/container/MiniProgramVisibilityTracker.kt")
        assertTrue(
            "the tracker must enumerate foreground apps for floatCurrentMiniProgram",
            tracker.contains("fun foregroundAppIds(): List<String>"),
        )
    }

    @Test
    fun `spec glyph inventory stays complete`() {
        assertEquals(
            "exactly one askAi float emblem painter",
            1,
            Regex("internal fun DrawScope.drawAskAiEmblem").findAll(buttonSource).count(),
        )
    }
}
