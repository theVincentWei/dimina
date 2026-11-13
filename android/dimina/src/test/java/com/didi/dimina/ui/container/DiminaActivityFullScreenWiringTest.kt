package com.didi.dimina.ui.container

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the WeChat-semantics immersive fullscreen wiring inside [DiminaActivity]
 * that pure-JVM tests cannot see. Assertions read the source text, same rationale
 * as [DiminaActivityCapsuleWiringTest].
 *
 * - the ONLY policy entry is FullScreenPolicy.shouldGoImmersive(runtimeType, window)
 *   driven from setInitialStyle (covers initialize / updatePath / onNewIntent);
 * - hiding is immersive STICKY (transient bars by swipe) and targets statusBars only;
 * - the cutout mode is guarded to P+ (minSdk 26) and uses SHORT_EDGES when immersive;
 * - onResume re-asserts hide only when immersive (non-fullscreen packages never
 *   touch system bars — historical behavior must stay untouched);
 * - the capsule anchor stays on systemBars ∪ displayCutout (no second channel).
 */
class DiminaActivityFullScreenWiringTest {

    private val activitySource: String by lazy {
        val relative = "src/main/kotlin/com/didi/dimina/ui/container/DiminaActivity.kt"
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("DiminaActivity.kt not found from ${File(".").absolutePath}")
        found.readText()
    }

    private val policySource: String by lazy {
        val relative = "src/main/kotlin/com/didi/dimina/common/FullScreenPolicy.kt"
        val candidates = listOf(relative, "dimina/$relative").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError("FullScreenPolicy.kt not found from ${File(".").absolutePath}")
        found.readText()
    }

    @Test
    fun `fullscreen decision is driven from setInitialStyle via FullScreenPolicy only`() {
        assertTrue(
            "setInitialStyle must consult FullScreenPolicy with app runtimeType + window",
            activitySource.contains(
                "FullScreenPolicy.shouldGoImmersive(appConfig.app.runtimeType, appConfig.app.window)",
            ),
        )
        assertTrue(
            "the decision must be made exactly once (single wiring point)",
            Regex("FullScreenPolicy\\.shouldGoImmersive").findAll(activitySource).count() == 1,
        )
        assertTrue(
            "policy must treat \"game\" runtime as immersive",
            policySource.contains("runtimeType == GAME_RUNTIME_TYPE"),
        )
        assertTrue(
            "policy must honor window.fullscreen for mini programs",
            policySource.contains("window?.fullscreen == true"),
        )
    }

    @Test
    fun `hiding is immersive sticky and status bars only`() {
        assertTrue(
            "behavior must be BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (swipe reveals, auto re-hides)",
            activitySource.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"),
        )
        assertTrue(
            "immersive mode hides statusBars",
            activitySource.contains("controller.hide(WindowInsetsCompat.Type.statusBars())"),
        )
        assertTrue(
            "clearing immersive shows statusBars back",
            activitySource.contains("controller.show(WindowInsetsCompat.Type.statusBars())"),
        )
        assertTrue(
            "navigation bars must NOT be hidden (status bar only, per WeChat fullscreen video semantics)",
            !activitySource.contains("hide(WindowInsetsCompat.Type.navigationBars())"),
        )
    }

    @Test
    fun `cutout short edges is P guarded`() {
        assertTrue(
            "SHORT_EDGES must be set when immersive so content may draw into the cutout",
            activitySource.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES"),
        )
        assertTrue(
            "cutout mode requires API P+ (minSdk 26) - guard against NoSuchFieldError",
            activitySource.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P"),
        )
    }

    @Test
    fun `onResume re asserts immersive only when enabled`() {
        assertTrue(
            "onResume must re-assert hide only for immersive packages",
            activitySource.contains("if (immersiveFullScreen.value) setImmersiveFullScreen(true)"),
        )
    }

    @Test
    fun `capsule anchor keeps the union inset channel`() {
        assertTrue(
            "fullscreen must not fork a second capsule channel - anchor stays systemBars.union(displayCutout)",
            activitySource.contains("ComposeWindowInsets.systemBars") &&
                activitySource.contains(".union(ComposeWindowInsets.displayCutout)"),
        )
    }

    @Test
    fun `window config exposes the fullscreen declaration`() {
        val appConfigSource = run {
            val relative = "src/main/kotlin/com/didi/dimina/bean/AppConfig.kt"
            val candidates = listOf(relative, "dimina/$relative").map(::File)
            candidates.firstOrNull(File::isFile)?.readText()
                ?: throw AssertionError("AppConfig.kt not found")
        }
        assertTrue(
            "WindowConfig must carry the nullable fullscreen declaration (absent = legacy packages unchanged)",
            appConfigSource.contains("val fullscreen: Boolean? = null"),
        )
    }
}
