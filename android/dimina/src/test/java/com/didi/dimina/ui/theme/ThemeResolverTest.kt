package com.didi.dimina.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the darkmode shell-value resolution contract: "@key" -> theme[current][key] ->
 * theme[other][key] -> bare literal with the "@" stripped; non-"@" values pass through
 * untouched so legacy packages that never declared darkmode keep byte-identical behavior.
 *
 * Pure JVM: [ThemeResolver.resolve] touches no Android types, so the fallback chain can be
 * exercised directly instead of pinning source text.
 */
class ThemeResolverTest {

    private val theme: Map<String, Map<String, String>> = mapOf(
        "light" to mapOf(
            "navBg" to "#ffffff",
            "shared" to "light-shared",
        ),
        "dark" to mapOf(
            "navBg" to "#191919",
            "shared" to "dark-shared",
        ),
    )

    @Test
    fun `non-at values pass through unchanged regardless of theme`() {
        assertEquals("#ffffff", ThemeResolver.resolve("light", theme, "#ffffff"))
        assertEquals("white", ThemeResolver.resolve("dark", theme, "white"))
        assertEquals("black", ThemeResolver.resolve("dark", null, "black"))
    }

    @Test
    fun `key resolves from the current theme first`() {
        assertEquals("#191919", ThemeResolver.resolve("dark", theme, "@navBg"))
        assertEquals("#ffffff", ThemeResolver.resolve("light", theme, "@navBg"))
    }

    @Test
    fun `key shared by both themes follows the current theme`() {
        assertEquals("dark-shared", ThemeResolver.resolve("dark", theme, "@shared"))
        assertEquals("light-shared", ThemeResolver.resolve("light", theme, "@shared"))
    }

    @Test
    fun `missing in current theme falls back to the other theme`() {
        val lightOnly = mapOf(
            "light" to mapOf("detailBg" to "#f7f7f7"),
            "dark" to emptyMap(),
        )
        assertEquals("#f7f7f7", ThemeResolver.resolve("dark", lightOnly, "@detailBg"))
    }

    @Test
    fun `missing everywhere falls back to the bare literal without at`() {
        assertEquals("missingKey", ThemeResolver.resolve("dark", theme, "@missingKey"))
    }

    @Test
    fun `missing theme table falls back to the bare literal without at`() {
        assertEquals("navBg", ThemeResolver.resolve("dark", null, "@navBg"))
        assertEquals("navBg", ThemeResolver.resolve("light", emptyMap(), "@navBg"))
    }

    @Test
    fun `bare at sign falls back to empty literal`() {
        // "@key 未定义 → 去掉 @ 用原字面值" 的极端情形:key 为空串,回退同为空串。
        assertEquals("", ThemeResolver.resolve("dark", theme, "@"))
    }

    @Test
    fun `otherTheme flips dark and light`() {
        assertEquals("light", ThemeResolver.otherTheme("dark"))
        assertEquals("dark", ThemeResolver.otherTheme("light"))
    }
}
