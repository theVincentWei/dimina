package com.didi.dimina.ui.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the capsule (···|◎) wiring inside [DiminaActivity] and [com.didi.dimina.Dimina] that
 * pure-JVM tests cannot see:
 *
 * - the capsule's vertical anchor is the SAME inset the Material3 app bar consumes
 *   (systemBars ∪ displayCutout, compose-exact) so the capsule centerline == title centerline;
 *   the old Utils px→dp round trip is a diverging second channel and must not come back;
 * - the WeChat-style default: translucent scrim over the resolved nav bar color + icon color
 *   from navigationBarTextStyle (real-time on theme flips, darkmode declared or not);
 * - host-explicit capsule colors (Builder setters) keep the highest priority;
 * - updateCapsuleTheme no longer carries solid capsule colors (menu panels only);
 * - the touch contract (menu / close / collapse) is untouched;
 * - the collapsed handle NEVER auto-expands (the 6s idle timer was removed on 2026-11-11:
 *   collapsing keeps the capsule hidden until the user taps the edge handle);
 * - the collapsed handle is a capsule-height hairline bar with an oversized hit area
 *   (capsule-footprint wide × 56dp tall), no background pill, no border.
 *
 * Assertions read the source text, same rationale as [DiminaActivityThemeWiringTest].
 */
class DiminaActivityCapsuleWiringTest {

    private val activitySource: String by lazy {
        val relative = "src/main/kotlin/com/didi/dimina/ui/container/DiminaActivity.kt"
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("DiminaActivity.kt not found from ${File(".").absolutePath}")
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
    fun `capsule vertical anchor is the same union inset the app bar consumes`() {
        assertTrue(
            "the capsule must anchor to systemBars.union(displayCutout) - the same top inset " +
                "CenterAlignedTopAppBar applies via TopAppBarDefaults.windowInsets",
            activitySource.contains("ComposeWindowInsets.systemBars") &&
                activitySource.contains(".union(ComposeWindowInsets.displayCutout)"),
        )
        assertTrue(
            "the vertical offset must center the capsule in the app bar content area " +
                "((64 - 32) / 2 = 16dp below the inset top)",
            activitySource.contains(
                "(MenuButtonLayout.NAVIGATION_BAR_CONTENT_HEIGHT_DP - MenuButtonLayout.HEIGHT_DP) / 2).dp",
            ),
        )
    }

    @Test
    fun `both capsule states consume the same-source vertical anchor`() {
        val occurrences = Regex(Regex.escape("top = capsuleTopPadding")).findAll(activitySource).count()
        assertTrue(
            "expanded capsule and collapsed handle must both use capsuleTopPadding (got $occurrences)",
            occurrences >= 2,
        )
        assertFalse(
            "the old Utils-anchored vertical placement (menuRect.top) must not come back",
            activitySource.contains("menuRect.top.dp"),
        )
    }

    @Test
    fun `default capsule colors follow the wechat semantics`() {
        assertTrue(
            "capsule fill must be a translucent scrim over the resolved nav bar color (CapsuleStyle)",
            activitySource.contains("CapsuleStyle.backgroundColor(") &&
                activitySource.contains("navBarColor = navBarBgColor.toArgb()"),
        )
        assertTrue(
            "capsule icons must follow navigationBarTextStyle (white → light icon)",
            activitySource.contains("CapsuleStyle.iconColor(") &&
                activitySource.contains("navigationBarTextColor.value == Color.White"),
        )
    }

    @Test
    fun `host-explicit capsule colors keep the highest priority`() {
        assertTrue(
            "DiminaConfig must expose whether the host explicitly configured capsule colors",
            diminaSource.contains("capsuleColorsExplicit"),
        )
        assertTrue(
            "the capsule must consult hasExplicitCapsuleColors() before applying the WeChat default",
            activitySource.contains("hasExplicitCapsuleColors()"),
        )
    }

    @Test
    fun `updateCapsuleTheme no longer carries solid capsule colors`() {
        val bodyStart = diminaSource.indexOf("fun updateCapsuleTheme(dark: Boolean)")
        assertTrue("updateCapsuleTheme must still exist for host compatibility", bodyStart >= 0)
        val body = diminaSource.substring(bodyStart, bodyStart + 400)
        assertFalse(
            "updateCapsuleTheme must not set solid capsule colors (0xFF2A2D34/WHITE) anymore - " +
                "the capsule adapts via navBarBgColor/navigationBarTextColor re-resolution",
            body.contains("0xFF2A2D34") || body.contains("android.graphics.Color.WHITE"),
        )
        assertFalse(
            "the solid bg/icon runtime overrides must be gone entirely",
            diminaSource.contains("capsuleBgOverride") || diminaSource.contains("capsuleIconOverride"),
        )
    }

    @Test
    fun `border and separator derive from the icon color`() {
        assertTrue(
            "hairline border/separator must derive from CapsuleStyle so they stay visible on " +
                "both light and dark nav bars",
            activitySource.contains("CapsuleStyle.borderColor(iconColor.toArgb())") &&
                activitySource.contains("CapsuleStyle.separatorColor(iconColor.toArgb())"),
        )
    }

    @Test
    fun `touch contract is preserved`() {
        assertTrue(
            "capsule more button must still open the mini program menu",
            activitySource.contains("onMoreClick = {\n                            showMiniProgramMenu.value = true\n                        }"),
        )
        assertTrue(
            "capsule close button must still hide the mini program",
            activitySource.contains("onCloseClick = { hideMiniProgram() }"),
        )
        assertTrue(
            "collapsed handle must still expand on tap",
            activitySource.contains("clickable { capsuleCollapsed.value = false }"),
        )
    }

    @Test
    fun `collapsed handle never auto-expands`() {
        assertFalse(
            "the 6s idle auto-expand timer must stay deleted - collapsing keeps the capsule " +
                "hidden until the user taps the edge handle",
            activitySource.contains("delay(6000"),
        )
        val expandTriggers =
            Regex("capsuleCollapsed\\.value\\s*=\\s*false").findAll(activitySource).count()
        assertTrue(
            "the ONLY expand trigger must be the handle tap (got $expandTriggers)",
            expandTriggers == 1,
        )
    }

    @Test
    fun `collapsed handle is capsule-height hairline with oversized hit area`() {
        assertTrue(
            "visual bar height must equal the capsule height (MenuButtonLayout.HEIGHT_DP)",
            activitySource.contains("height = MenuButtonLayout.HEIGHT_DP.dp"),
        )
        assertTrue(
            "the hairline width/alpha/hit-area must be named constants (COLLAPSED_HANDLE_*)",
            activitySource.contains("COLLAPSED_HANDLE_VISUAL_WIDTH_DP") &&
                activitySource.contains("COLLAPSED_HANDLE_VISUAL_ALPHA") &&
                activitySource.contains("COLLAPSED_HANDLE_HIT_WIDTH_DP") &&
                activitySource.contains("COLLAPSED_HANDLE_HIT_OVERHANG_DP"),
        )
        assertFalse(
            "the old bordered pill (fixed black 0x33000000 border) must not come back",
            activitySource.contains("0x33000000"),
        )
    }
}
