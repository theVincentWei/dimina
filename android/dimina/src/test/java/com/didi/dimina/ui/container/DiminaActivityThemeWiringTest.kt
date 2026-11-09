package com.didi.dimina.ui.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the darkmode theme wiring inside [DiminaActivity] that the pure-JVM resolver and merge
 * tests cannot see:
 *
 * - setInitialStyle records the RAW (possibly "@key") values and applies them through
 *   reapplyThemeStyles - so every navigation re-resolves page-level window keys against the
 *   *current* theme with no extra listener (WeChat page config support);
 * - onConfigurationChanged re-runs reapplyThemeStyles - the tick that flips an already-loaded
 *   page, and every page in the stack is its own Activity, so background pages re-resolve too;
 * - resolution is gated on darkmode, legacy packages stay byte-identical;
 * - the theme push to JS uses peekJsCore (never creates) and only on a real theme flip.
 *
 * Assertions read the source text. Driving a real Activity would need an Android runtime,
 * which this module's unit tests do not have. Each lookup throws when the declaration is
 * missing, so a rename fails the test instead of quietly unpinning the wiring.
 */
class DiminaActivityThemeWiringTest {

    private val relativeSourcePath = "src/main/kotlin/com/didi/dimina/ui/container/DiminaActivity.kt"

    private val source: String by lazy {
        val candidates = listOf(relativeSourcePath, "dimina/$relativeSourcePath").map(::File)
        val found = candidates.firstOrNull(File::isFile)
            ?: throw AssertionError(
                "DiminaActivity.kt not found from working directory ${File(".").absolutePath}; " +
                    "tried ${candidates.map(File::getPath)}.",
            )
        found.readText()
    }

    /** The brace-matched body of `fun [name]`, or a failure if that declaration is gone. */
    private fun bodyOf(name: String): String {
        val signature = Regex("""\n\s*(?:(?:private|internal)\s+)?(?:override\s+)?fun $name\(""").find(source)
            ?: throw AssertionError("DiminaActivity no longer declares `fun $name(`")
        val open = source.indexOf('{', signature.range.last)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("Could not brace-match `fun $name(`")
    }

    @Test
    fun `setInitialStyle records raw values and re-resolves them in the same call`() {
        val body = bodyOf("setInitialStyle")
        assertTrue(
            "setInitialStyle must record the raw navigationBarBackgroundColor for later re-resolution",
            body.contains("rawNavigationBarBackgroundColor = config.navigationBarBackgroundColor"),
        )
        assertTrue(
            "setInitialStyle must record the raw backgroundColor for later re-resolution",
            body.contains("rawBackgroundColor = config.backgroundColor"),
        )
        assertTrue(
            "setInitialStyle must record the raw navigationBarTextStyle for later re-resolution",
            body.contains("rawNavigationBarTextStyle = config.navigationBarTextStyle"),
        )
        assertTrue(
            "setInitialStyle must apply styles through reapplyThemeStyles (current-theme resolution)",
            body.contains("reapplyThemeStyles()"),
        )
    }

    @Test
    fun `reapplyThemeStyles resolves every shell apply point and every tab page config`() {
        val body = bodyOf("reapplyThemeStyles")
        assertTrue(
            "navigation bar background must go through resolveThemeValue",
            body.contains("navigationBarBackgroundColor.value = resolveThemeValue(rawNavBg)"),
        )
        assertTrue(
            "page background must go through resolveThemeValue",
            body.contains("backgroundColor.value = resolveThemeValue(rawPageBg)"),
        )
        assertTrue(
            "system navigation bar must be re-applied manually on tick",
            body.contains("updateSystemNavigationBarColor(backgroundColor.value)"),
        )
        assertTrue(
            "webview background must be re-applied manually on tick",
            body.contains("updateWebViewBackgroundColor(backgroundColor.value)"),
        )
        assertTrue(
            "status bar text style must be re-resolved on tick",
            body.contains("updateActionColorStyle(resolveThemeValue(rawTextStyle))"),
        )
        assertTrue(
            "background tab pages must re-resolve their own page config, not the active one",
            body.contains("resolveThemeValue(state.configInfo.backgroundColor)"),
        )
    }

    @Test
    fun `theme resolution is gated on darkmode and reads the live uiMode`() {
        val body = bodyOf("resolveThemeValue")
        assertTrue(
            "resolveThemeValue must early-return raw values when darkmode is not declared",
            body.contains("if (!isDarkModeEnabled())"),
        )
        assertTrue(
            "resolveThemeValue must delegate to ThemeResolver with the live uiMode-derived theme",
            body.contains("ThemeResolver.resolve(ThemeResolver.currentTheme(this)") &&
                body.contains("appConfig.app.theme"),
        )
    }

    @Test
    fun `theme flip re-resolves shell styles and pushes a patch only on a real flip`() {
        val body = bodyOf("onConfigurationChanged")
        assertTrue(
            "onConfigurationChanged must re-run reapplyThemeStyles for the loaded page",
            body.contains("reapplyThemeStyles()"),
        )
        assertTrue(
            "the JS theme push must be gated on darkmode - packages without darkmode are never notified",
            body.contains("isDarkModeEnabled()"),
        )
        assertTrue(
            "wx.onThemeChange fires on real flips only - compare against lastPushedJsTheme",
            body.contains("!= lastPushedJsTheme"),
        )
        assertTrue(
            "onConfigurationChanged must push the systemInfo patch via pushSystemInfoPatchToJsCore",
            body.contains("pushSystemInfoPatchToJsCore()"),
        )
    }

    @Test
    fun `tabBar colors resolve all four fields through the theme`() {
        val body = bodyOf("resolveTabBarColors")
        assertTrue(
            "resolveTabBarColors must early-return the same config when darkmode is not declared",
            body.contains("if (!isDarkModeEnabled())"),
        )
        for (field in listOf("color", "selectedColor", "backgroundColor", "borderStyle")) {
            assertTrue(
                "tabBar $field must go through resolveThemeValue",
                body.contains("resolveThemeValue(config.$field)"),
            )
        }
    }

    @Test
    fun `js theme push peeks the core without creating one and sends a full systemInfo patch`() {
        val body = bodyOf("pushSystemInfoPatchToJsCore")
        assertTrue(
            "the push must use peekJsCore - a missing JsCore stays silent, never created",
            body.contains("peekJsCore"),
        )
        assertFalse(
            "the push must not create a JsCore as a side effect (getJsCore would)",
            body.contains("getJsCore"),
        )
        assertTrue(
            "the patch must merge via the hostEnvUpdate message type the base library dispatches",
            body.contains("\"hostEnvUpdate\""),
        )
        assertTrue(
            "the patch must carry the full systemInfo - a bare theme object would wipe the rest of the hostEnv snapshot",
            body.contains("getMiniProgramSystemInfo"),
        )
    }

    @Test
    fun `tab bar rendering consumes the theme-resolved config recomposed by themeTick`() {
        assertTrue(
            "the tab bar config must be re-resolved keyed on themeTick so a system flip recomposes it",
            source.contains("remember(tabBarConfig, themeTick.intValue)"),
        )
        assertTrue(
            "DiminaTabBar must receive the resolved config, not the raw one",
            source.contains("resolvedTabBarConfig?.takeIf { shouldShowTabBar }"),
        )
    }

    @Test
    fun `systemInfo patch carries the current theme so onThemeChange can fire`() {
        val body = bodyOf("pushSystemInfoPatchToJsCore")
        assertTrue(
            "the patch builder must inject currentJsTheme - Utils.getMiniProgramSystemInfo has no theme, " +
                "and the base library drops theme-less patches (if (!theme) return)",
            body.contains("""currentJsTheme()?.let { systemInfo.put("theme", it) }"""),
        )
    }

    @Test
    fun `webview force dark is gated on darkmode with feature check`() {
        val body = bodyOf("applyWebViewColorScheme")
        assertTrue(
            "force dark must be feature-checked - unsupported WebView versions must stay untouched",
            body.contains("WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)"),
        )
        assertTrue(
            "force dark must only turn ON for packages that declared darkmode",
            body.contains("if (isDarkModeEnabled())") &&
                body.contains("WebSettingsCompat.FORCE_DARK_ON") &&
                body.contains("WebSettingsCompat.FORCE_DARK_OFF"),
        )
    }

    @Test
    fun `webview force dark is applied at entry, creation and theme ticks`() {
        val withWebViewBody = bodyOf("withWebView")
        assertEquals(
            "both withWebView immediate branches must apply the scheme before the action",
            2,
            Regex("applyWebViewColorScheme\\(it\\)").findAll(withWebViewBody).count(),
        )
        assertTrue(
            "reapplyThemeStyles must re-cover every created webview (main + all tab pages)",
            bodyOf("reapplyThemeStyles").contains("forEach(::applyWebViewColorScheme)"),
        )
        assertTrue(
            "the main webview creation point must set the scheme before first paint",
            bodyOf("onWebViewReady").contains("applyWebViewColorScheme(webView)"),
        )
        assertTrue(
            "the tab webview creation point must set the scheme before first paint",
            bodyOf("onTabWebViewReady").contains("applyWebViewColorScheme(webView)"),
        )
    }
}
